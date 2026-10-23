/*
 * AF_PACKET raw frame sender for monitor interface test
 * Build: aarch64-linux-gnu-gcc -O2 -static -o pfinject pfinject.c
 * Usage: pfinject <ifname> <hexframe> [radiotap]
 */
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <unistd.h>
#include <errno.h>
#include <arpa/inet.h>
#include <net/if.h>
#include <sys/socket.h>
#include <linux/if_packet.h>
#include <linux/if_ether.h>

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
    if (!ifidx) { perror("if_nametoindex"); return 2; }

    unsigned char frame[2048];
    int flen = hex2bin(argv[2], frame, sizeof(frame));
    if (flen <= 0) { fprintf(stderr, "bad hex\n"); return 2; }

    int fd = socket(AF_PACKET, SOCK_RAW, htons(ETH_P_ALL));
    if (fd < 0) { perror("socket"); return 2; }

    struct sockaddr_ll sll;
    memset(&sll, 0, sizeof(sll));
    sll.sll_family = AF_PACKET;
    sll.sll_ifindex = ifidx;
    sll.sll_protocol = htons(ETH_P_ALL);
    sll.sll_halen = 6;
    memset(sll.sll_addr, 0xff, 6);

    ssize_t n = sendto(fd, frame, flen, 0, (struct sockaddr *)&sll, sizeof(sll));
    if (n < 0) {
        printf("PF_SEND_FAIL error=%d (%s)\n", errno, strerror(errno));
        return 1;
    }
    printf("PF_SEND_OK sent=%zd\n", n);
    return 0;
}
