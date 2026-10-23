/*
 * nl80211 frame injector for Android/Qualcomm test
 * Build: aarch64-linux-gnu-gcc -O2 -static -o nlinject nlinject.c
 * Usage: nlinject <ifname> <hexframe>
 * Returns 0 on ACK, otherwise prints errno.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <linux/netlink.h>
#include <linux/genetlink.h>
#include <linux/nl80211.h>
#include <net/if.h>

#ifndef NLA_DATA
#define NLA_DATA(na) ((void *)((char *)(na) + NLA_HDRLEN))
#endif
#ifndef NLA_OK
#define NLA_OK(na, len) ((len) >= (int)sizeof(struct nlattr) && \
    (na)->nla_len >= sizeof(struct nlattr) && (na)->nla_len <= (len))
#endif
#ifndef NLA_NEXT
#define NLA_NEXT(na, attrlen) ((attrlen) -= NLA_ALIGN((na)->nla_len), \
    (struct nlattr *)((char *)(na) + NLA_ALIGN((na)->nla_len)))
#endif

static int nl_fd;
static unsigned int nl_seq;
static int nl80211_id;

static int send_and_ack(struct nlmsghdr *nlh)
{
    struct sockaddr_nl sa = { .nl_family = AF_NETLINK };
    char buf[8192];
    int ret;

    nlh->nlmsg_seq = ++nl_seq;
    nlh->nlmsg_pid = 0;
    nlh->nlmsg_flags |= NLM_F_REQUEST | NLM_F_ACK;

    if (sendto(nl_fd, nlh, nlh->nlmsg_len, 0,
               (struct sockaddr *)&sa, sizeof(sa)) < 0)
        return -errno;

    for (;;) {
        ret = recv(nl_fd, buf, sizeof(buf), 0);
        if (ret < 0)
            return -errno;

        for (struct nlmsghdr *h = (struct nlmsghdr *)buf; NLMSG_OK(h, ret);
             h = NLMSG_NEXT(h, ret)) {
            if (h->nlmsg_seq != nl_seq)
                continue;
            if (h->nlmsg_type == NLMSG_ERROR) {
                struct nlmsgerr *e = (struct nlmsgerr *)NLMSG_DATA(h);
                return e->error;
            }
            if (h->nlmsg_type == NLMSG_DONE)
                return 0;
        }
    }
}

static int resolve_nl80211(void)
{
    char buf[4096];
    struct nlmsghdr *nlh = (struct nlmsghdr *)buf;
    struct genlmsghdr *gh;
    struct nlattr *na;
    struct sockaddr_nl sa = { .nl_family = AF_NETLINK };
    int ret, len;
    const char *name = "nl80211";

    memset(buf, 0, sizeof(buf));
    nlh->nlmsg_len = NLMSG_LENGTH(GENL_HDRLEN);
    nlh->nlmsg_type = GENL_ID_CTRL;
    nlh->nlmsg_flags = NLM_F_REQUEST;
    nlh->nlmsg_seq = ++nl_seq;

    gh = (struct genlmsghdr *)NLMSG_DATA(nlh);
    gh->cmd = CTRL_CMD_GETFAMILY;
    gh->version = 1;

    na = (struct nlattr *)((char *)gh + GENL_HDRLEN);
    na->nla_type = CTRL_ATTR_FAMILY_NAME;
    na->nla_len = NLA_HDRLEN + strlen(name) + 1;
    strcpy((char *)NLA_DATA(na), name);

    nlh->nlmsg_len = NLMSG_LENGTH(GENL_HDRLEN) + NLA_ALIGN(na->nla_len);
    nlh->nlmsg_flags |= NLM_F_ACK;

    if (sendto(nl_fd, nlh, nlh->nlmsg_len, 0,
               (struct sockaddr *)&sa, sizeof(sa)) < 0)
        return -errno;

    len = recv(nl_fd, buf, sizeof(buf), 0);
    if (len < 0)
        return -errno;

    for (struct nlmsghdr *h = (struct nlmsghdr *)buf; NLMSG_OK(h, len);
         h = NLMSG_NEXT(h, len)) {
        if (h->nlmsg_type == NLMSG_ERROR) {
            struct nlmsgerr *e = (struct nlmsgerr *)NLMSG_DATA(h);
            if (e->error)
                return e->error;
            continue;
        }
        gh = (struct genlmsghdr *)NLMSG_DATA(h);
        int alen = h->nlmsg_len - NLMSG_LENGTH(GENL_HDRLEN);
        for (na = (struct nlattr *)((char *)gh + GENL_HDRLEN);
             NLA_OK(na, alen); na = NLA_NEXT(na, alen)) {
            if (na->nla_type == CTRL_ATTR_FAMILY_ID) {
                nl80211_id = *(unsigned short *)NLA_DATA(na);
                return 0;
            }
        }
    }
    return -ENOENT;
}

static int hex2bin(const char *hex, unsigned char *out, int max)
{
    int n = 0;
    while (*hex && n < max) {
        if (*hex == ':' || *hex == ' ') { hex++; continue; }
        if (!hex[1]) return -1;
        unsigned int v;
        if (sscanf(hex, "%2x", &v) != 1) return -1;
        out[n++] = (unsigned char)v;
        hex += 2;
    }
    return n;
}

int main(int argc, char **argv)
{
    if (argc < 3) {
        fprintf(stderr, "usage: %s <ifname> <hexframe>\n", argv[0]);
        return 2;
    }

    int ifidx = if_nametoindex(argv[1]);
    if (ifidx == 0) {
        fprintf(stderr, "if_nametoindex(%s): %s\n", argv[1], strerror(errno));
        return 2;
    }

    unsigned char frame[2048];
    int flen = hex2bin(argv[2], frame, sizeof(frame));
    if (flen <= 0) {
        fprintf(stderr, "bad hex frame\n");
        return 2;
    }

    nl_fd = socket(AF_NETLINK, SOCK_RAW, NETLINK_GENERIC);
    if (nl_fd < 0) {
        perror("socket");
        return 2;
    }
    struct sockaddr_nl local = { .nl_family = AF_NETLINK };
    if (bind(nl_fd, (struct sockaddr *)&local, sizeof(local)) < 0) {
        perror("bind");
        return 2;
    }

    int r = resolve_nl80211();
    if (r < 0) {
        fprintf(stderr, "resolve nl80211: %s\n", strerror(-r));
        return 2;
    }

    char buf[4096];
    memset(buf, 0, sizeof(buf));
    struct nlmsghdr *nlh = (struct nlmsghdr *)buf;
    struct genlmsghdr *gh;
    struct nlattr *na;

    nlh->nlmsg_len = NLMSG_LENGTH(GENL_HDRLEN);
    nlh->nlmsg_type = nl80211_id;
    nlh->nlmsg_flags = NLM_F_REQUEST;

    gh = (struct genlmsghdr *)NLMSG_DATA(nlh);
    gh->cmd = NL80211_CMD_FRAME;
    gh->version = 0;

    /* NL80211_ATTR_IFINDEX */
    na = (struct nlattr *)((char *)nlh + nlh->nlmsg_len);
    na->nla_type = NL80211_ATTR_IFINDEX;
    na->nla_len = NLA_HDRLEN + 4;
    *(int *)NLA_DATA(na) = ifidx;
    nlh->nlmsg_len += NLA_ALIGN(na->nla_len);

    /* NL80211_ATTR_WIPHY_FREQ */
    na = (struct nlattr *)((char *)nlh + nlh->nlmsg_len);
    na->nla_type = NL80211_ATTR_WIPHY_FREQ;
    na->nla_len = NLA_HDRLEN + 4;
    *(int *)NLA_DATA(na) = 2437; /* channel 6 */
    nlh->nlmsg_len += NLA_ALIGN(na->nla_len);

    /* NL80211_ATTR_FRAME */
    na = (struct nlattr *)((char *)nlh + nlh->nlmsg_len);
    na->nla_type = NL80211_ATTR_FRAME;
    na->nla_len = NLA_HDRLEN + flen;
    memcpy(NLA_DATA(na), frame, flen);
    nlh->nlmsg_len += NLA_ALIGN(na->nla_len);

    /* NL80211_ATTR_DONT_WAIT_FOR_ACK */
    na = (struct nlattr *)((char *)nlh + nlh->nlmsg_len);
    na->nla_type = NL80211_ATTR_DONT_WAIT_FOR_ACK;
    na->nla_len = NLA_HDRLEN;
    nlh->nlmsg_len += NLA_ALIGN(na->nla_len);

    nlh->nlmsg_len = NLMSG_ALIGN(nlh->nlmsg_len);

    r = send_and_ack(nlh);
    if (r == 0) {
        printf("INJECT_OK ifindex=%d frame_len=%d\n", ifidx, flen);
        return 0;
    }
    printf("INJECT_FAIL error=%d (%s)\n", r, strerror(-r));
    return 1;
}
