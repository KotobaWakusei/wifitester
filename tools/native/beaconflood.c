/*
 * beaconflood - minimal 802.11 beacon injector for the WiFiTester toolchain.
 *
 * Broadcasts forged beacon frames so that nearby devices see one or more
 * access points with a chosen SSID. Intended for testing the beacon module
 * of the workbench on networks the operator owns or is authorized to test.
 *
 *   beaconflood -i <monitor-iface> [-s <ssid>] [-n <ap-count>] [-r <fps>]
 *               [-d <seconds>] [-c <channel>]
 *
 * Defaults: SSID "114514", 20 access points with locally administered,
 * randomized MAC addresses, 50 frames per second, 30 seconds. The tool
 * stops on SIGINT/SIGTERM or when the duration elapses and prints a small
 * statistic. The frame carries a minimal radiotap header (no fields), which
 * mac80211 drivers accept for transmitted frames.
 *
 * Author: part of the WiFiTester bundled toolchain (GPL-2.0-or-later).
 */

#define _GNU_SOURCE

#include <errno.h>
#include <linux/if_packet.h>
#include <net/if.h>
#include <netinet/in.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

#define MAX_SSID_LEN 32
#define FRAME_MAX 256
#define DEFAULT_AP_COUNT 20
#define MAX_AP_COUNT 100
#define DEFAULT_FPS 50
#define MAX_FPS 500
#define DEFAULT_DURATION 30
#define MAX_DURATION 300
#define FRAMES_PER_AP 100

static volatile sig_atomic_t stop_requested;

static void on_signal(int signo) {
    (void)signo;
    stop_requested = 1;
}

static uint64_t now_micros(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000ULL + (uint64_t)(ts.tv_nsec / 1000);
}

static void sleep_micros(uint64_t micros) {
    struct timespec ts;
    ts.tv_sec = (time_t)(micros / 1000000ULL);
    ts.tv_nsec = (long)(micros % 1000000ULL) * 1000L;
    nanosleep(&ts, NULL);
}

static void random_bytes(uint8_t *out, size_t len) {
    static FILE *urandom;
    if (urandom == NULL) {
        urandom = fopen("/dev/urandom", "rb");
        if (urandom == NULL) {
            srandom((unsigned)getpid());
        }
    }
    if (urandom != NULL) {
        if (fread(out, 1, len, urandom) == len) return;
    }
    for (size_t i = 0; i < len; i++) {
        out[i] = (uint8_t)(random() & 0xff);
    }
}

/* Locally administered, unicast MAC: first byte 0x42 (01000010). */
static void random_mac(uint8_t *mac) {
    random_bytes(mac, 6);
    mac[0] = 0x42;
    mac[5] = (uint8_t)(mac[5] & 0xfe); /* unicast */
}

static int open_packet_socket(const char *interface_name, int *ifindex) {
    int fd = socket(AF_PACKET, SOCK_RAW, htons(ETH_P_ALL));
    if (fd < 0) {
        return -1;
    }
    struct ifreq request;
    memset(&request, 0, sizeof(request));
    strncpy(request.ifr_name, interface_name, IFNAMSIZ - 1);
    if (ioctl(fd, SIOCGIFINDEX, &request) < 0) {
        int saved = errno;
        close(fd);
        errno = saved;
        return -1;
    }
    *ifindex = request.ifr_ifindex;

    struct sockaddr_ll address;
    memset(&address, 0, sizeof(address));
    address.sll_family = AF_PACKET;
    address.sll_protocol = htons(ETH_P_ALL);
    address.sll_ifindex = *ifindex;
    address.sll_hatype = 0;
    address.sll_pkttype = 0;
    address.sll_halen = 0;
    if (bind(fd, (struct sockaddr *)&address, sizeof(address)) < 0) {
        int saved = errno;
        close(fd);
        errno = saved;
        return -1;
    }
    return fd;
}

/*
 * Builds one beacon frame into frame[] and returns its length.
 * Layout: radiotap(8) + 802.11 fixed+addr(24) + timestamp(8) +
 * beacon interval(2) + capability(2) + IEs. All 802.11 fields are
 * little-endian per the standard.
 */
static size_t build_beacon(uint8_t *frame, size_t frame_size,
                           const uint8_t *bssid, const char *ssid,
                           int channel, uint16_t sequence) {
    size_t ssid_len = strlen(ssid);
    if (ssid_len > MAX_SSID_LEN) ssid_len = MAX_SSID_LEN;
    /* radiotap(8) + 24 + 8 + 2 + 2 + (2+ssid) + (2+8) + (2+1) */
    size_t length = 8 + 24 + 8 + 2 + 2 + 2 + ssid_len + 2 + 8 + 2 + 1;
    if (length > frame_size) return 0;

    size_t offset = 0;
    memcpy(frame + offset, "\x00\x00\x08\x00\x00\x00\x00\x00", 8);
    offset += 8;                                    /* radiotap: no fields */

    frame[offset++] = 0x80;                         /* type mgmt, subtype beacon */
    frame[offset++] = 0x00;
    frame[offset++] = 0x00;                         /* duration */
    frame[offset++] = 0x00;
    memset(frame + offset, 0xff, 6);                /* addr1: broadcast */
    offset += 6;
    memcpy(frame + offset, bssid, 6);               /* addr2: sender */
    offset += 6;
    memcpy(frame + offset, bssid, 6);               /* addr3: BSSID */
    offset += 6;
    frame[offset++] = (uint8_t)(sequence & 0xf0);   /* sequence control */
    frame[offset++] = (uint8_t)((sequence >> 8) & 0x0f);

    uint64_t timestamp = (uint64_t)time(NULL) * 1000000ULL;
    for (int i = 0; i < 8; i++) {                   /* timestamp */
        frame[offset++] = (uint8_t)(timestamp >> (8 * i));
    }
    frame[offset++] = 0x64;                         /* beacon interval: 100 TU */
    frame[offset++] = 0x00;
    frame[offset++] = 0x01;                         /* capability: ESS */
    frame[offset++] = 0x00;

    frame[offset++] = 0x00;                         /* IE: SSID */
    frame[offset++] = (uint8_t)ssid_len;
    memcpy(frame + offset, ssid, ssid_len);
    offset += ssid_len;

    static const uint8_t rates[] = {0x82, 0x84, 0x8b, 0x96, 0x0c, 0x12, 0x18, 0x24};
    frame[offset++] = 0x01;                         /* IE: supported rates */
    frame[offset++] = (uint8_t)(sizeof(rates));
    memcpy(frame + offset, rates, sizeof(rates));
    offset += sizeof(rates);

    frame[offset++] = 0x03;                         /* IE: DS parameter set */
    frame[offset++] = 0x01;
    frame[offset++] = (uint8_t)channel;

    return offset;
}

static void usage(const char *program) {
    fprintf(stderr,
            "Usage: %s -i <monitor-iface> [-s <ssid>] [-n <ap-count>] "
            "[-r <frames-per-sec>] [-d <seconds>] [-c <channel>]\n"
            "  -s  SSID to advertise (default: 114514, max 32 bytes)\n"
            "  -n  number of distinct access points (default: 20, max: 100)\n"
            "  -r  beacon frames per second per AP (default: 50, max: 500)\n"
            "  -d  duration in seconds (default: 30, max: 300)\n"
            "  -c  channel number written into the DS IE (default: 6)\n",
            program);
}

int main(int argc, char **argv) {
    const char *interface_name = NULL;
    const char *ssid = "114514";
    int ap_count = DEFAULT_AP_COUNT;
    int fps = DEFAULT_FPS;
    int duration = DEFAULT_DURATION;
    int channel = 6;

    int option;
    while ((option = getopt(argc, argv, "i:s:n:r:d:c:h")) != -1) {
        switch (option) {
            case 'i': interface_name = optarg; break;
            case 's': ssid = optarg; break;
            case 'n': ap_count = atoi(optarg); break;
            case 'r': fps = atoi(optarg); break;
            case 'd': duration = atoi(optarg); break;
            case 'c': channel = atoi(optarg); break;
            case 'h':
            default:
                usage(argv[0]);
                return option == 'h' ? 0 : 1;
        }
    }

    if (interface_name == NULL || strlen(interface_name) == 0) {
        usage(argv[0]);
        return 1;
    }
    if (strlen(ssid) > MAX_SSID_LEN) {
        fprintf(stderr, "SSID exceeds %d bytes\n", MAX_SSID_LEN);
        return 1;
    }
    if (ap_count < 1 || ap_count > MAX_AP_COUNT) {
        fprintf(stderr, "ap-count must be 1..%d\n", MAX_AP_COUNT);
        return 1;
    }
    if (fps < 1 || fps > MAX_FPS) {
        fprintf(stderr, "frames-per-sec must be 1..%d\n", MAX_FPS);
        return 1;
    }
    if (duration < 1 || duration > MAX_DURATION) {
        fprintf(stderr, "duration must be 1..%d\n", MAX_DURATION);
        return 1;
    }

    signal(SIGINT, on_signal);
    signal(SIGTERM, on_signal);

    int ifindex = 0;
    int fd = open_packet_socket(interface_name, &ifindex);
    if (fd < 0) {
        fprintf(stderr, "cannot open raw socket on %s: %s\n",
                interface_name, strerror(errno));
        return 1;
    }

    uint8_t (*macs)[6] = malloc((size_t)ap_count * 6);
    if (macs == NULL) {
        close(fd);
        return 1;
    }
    for (int i = 0; i < ap_count; i++) {
        random_mac(macs[i]);
    }

    printf("beaconflood: iface=%s ssid=\"%s\" aps=%d fps=%d duration=%ds channel=%d\n",
           interface_name, ssid, ap_count, fps, duration, channel);

    uint8_t frame[FRAME_MAX];
    long sent = 0;
    long failed = 0;
    uint64_t started = now_micros();
    uint64_t deadline = started + (uint64_t)duration * 1000000ULL;
    uint64_t interval = 1000000ULL / (uint64_t)fps;
    int current_ap = 0;
    uint64_t ap_switch = started + 1000000ULL;  /* one AP per second */
    uint16_t sequence = 0;

    while (!stop_requested && now_micros() < deadline) {
        if (now_micros() >= ap_switch) {
            current_ap = (current_ap + 1) % ap_count;
            ap_switch = now_micros() + 1000000ULL;
        }
        size_t length = build_beacon(frame, sizeof(frame), macs[current_ap],
                                     ssid, channel, sequence);
        sequence = (uint16_t)((sequence + 0x10) & 0xfff0);
        if (length == 0) continue;
        if (sendto(fd, frame, length, 0, NULL, 0) >= 0) {
            sent++;
        } else {
            failed++;
        }
        sleep_micros(interval);
    }

    close(fd);
    free(macs);
    printf("beaconflood: done. sent=%ld failed=%ld elapsed=%.1fs\n",
           sent, failed, (now_micros() - started) / 1000000.0);
    return 0;
}
