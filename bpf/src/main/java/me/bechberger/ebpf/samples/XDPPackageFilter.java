package me.bechberger.ebpf.samples;

import me.bechberger.ebpf.annotations.bpf.BPF;
import me.bechberger.ebpf.annotations.bpf.BPFMapDefinition;
import me.bechberger.ebpf.bpf.BPFProgram;
import me.bechberger.ebpf.bpf.XDPUtil;
import me.bechberger.ebpf.bpf.map.BPFHashMap;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.net.*;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import static java.lang.System.*;

/**
 * Use XDP to block incoming packages from specific URLs
 * <p>
 * Based on the code from <a href="https://sematext.com/blog/ebpf-and-xdp-for-processing-packets-at-bare-metal-speed/">sematext.com</a>.
 * Albeit this code can be found in many other places, as
 * it is the most straightforward example of using XDP to block incoming packages.
 */
@BPF(license = "GPL")
@Command(name = "XDPPackageFilter", mixinStandardHelpOptions = true, version = "XDPPackageFilter 1.0",
        description = "Use XDP to block incoming IPv4 packages from a URLs")
public abstract class XDPPackageFilter extends BPFProgram implements Runnable {

    @BPFMapDefinition(maxEntries = 256 * 4096)
    BPFHashMap<Integer, Boolean> blockedIPs;

    @BPFMapDefinition(maxEntries = 256 * 4096)
    BPFHashMap<Integer, Integer> blockingStats;

    private static final String EBPF_PROGRAM = """
            #include <vmlinux.h>
            #include <bpf/bpf_helpers.h>
            
            // copied from the linux kernel
            #define AF_INET 2
            #define AF_INET6 10
            
            #define ETH_ALEN 6
            #define ETH_P_802_3_MIN 0x0600
            #define ETH_P_8021Q 0x8100
            #define ETH_P_8021AD 0x88A8
            #define ETH_P_IP 0x0800
            #define ETH_P_IPV6 0x86DD
            #define ETH_P_ARP 0x0806
            #define IPPROTO_ICMPV6 58
            
            SEC("xdp")
            int xdp_pass(struct xdp_md *ctx) {
                void *end = (void *)(long)ctx->data_end;
                void *data = (void *)(long)ctx->data;
                u32 ip_src;
                u64 offset;
                u16 eth_type;
            
                struct ethhdr *eth = data;
                offset = sizeof(*eth);
            
                if (data + offset > end) {
                    // ethernet package header is incomplete
                    return XDP_ABORTED;
                }
                eth_type = eth->h_proto;
            
                /* handle VLAN tagged packet */
                if (eth_type == ETH_P_8021Q || eth_type == ETH_P_8021AD) {
                    struct vlan_hdr *vlan_hdr;
            
                    vlan_hdr = (void *)eth + offset;
                    offset += sizeof(*vlan_hdr);
                    if ((void *)eth + offset > end) {
                        // ethernet package header is incomplete
                        return false;
                    }
                    eth_type = vlan_hdr->h_vlan_encapsulated_proto;
                }
            
                /* let's only handle IPv4 addresses and ignore ARP packages */
                if (eth_type == ETH_P_IPV6 || eth_type == ETH_P_ARP) {
                    return XDP_PASS;
                }
            
                struct iphdr *iph = data + offset;
                offset += sizeof(struct iphdr);
                /* make sure the bytes you want to read are within the packet's range before reading them */
                if (iph + 1 > end) {
                    return XDP_ABORTED;
                }
                ip_src = iph->saddr;
            
                // find entry in block list
                void* ret = (void*)bpf_map_lookup_elem(&blockedIPs, &ip_src);
                if (!ret) {
                    return XDP_PASS;
                }
                if (*(s8*)ret) { // log if requested
                    bpf_printk("IP source address: %d.%d.%d.%d", (ip_src >> 0) & 0xff, (ip_src >> 8) & 0xff, (ip_src >> 16) & 0xff, (ip_src >> 24) & 0xff);
                }
            
                // count the number of blocked packages
                s32* counter = bpf_map_lookup_elem(&blockingStats, &ip_src);
                if (counter) {
                    *counter += 1;
                } else {
                    u64 value = 1;
                    bpf_map_update_elem(&blockingStats, &ip_src, &value, BPF_ANY);
                }
            
                return XDP_DROP;
            }
            """;

    @Parameters(arity = "1..*", description = "URLs to block")
    private String[] blockedUrls;

    @Option(names = "--run-url-retrieve-loop", description = "Try to retrieve the content of the first URL in a loop")
    private boolean runURLRetrieveLoop;

    private Map<Integer, String> ipToUrlMap;

    void setupBlockedIPMap() {
        ipToUrlMap = Arrays.stream(blockedUrls).flatMap(url -> {
            try {
                return Arrays.stream(InetAddress.getAllByName(url)).map(addr -> Map.entry(XDPUtil.ipAddressToInt(addr), url));
            } catch (UnknownHostException e) {
                throw new RuntimeException(e);
            }
        }).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        ipToUrlMap.keySet().forEach(ip -> {
            blockedIPs.put(ip, true);
        });
    }

    void printBlockedLog() {
        out.println("Blocked packages:");
        blockingStats.forEach((ip, count) -> {
            out.println("  Blocked " + count + " packages from " +
                    XDPUtil.intToIpAddress(ip) +
                    " (" + ipToUrlMap.get(ip) + ")");
        });
    }

    @Override
    public void run() {
        setupBlockedIPMap();
        if (runURLRetrieveLoop) {
            XDPUtil.openURLInLoop(blockedUrls[0]);
        }
        xdpAttach(getProgramByName("xdp_pass"), XDPUtil.getNetworkInterfaceIndex());
        while (true) {
            printBlockedLog();
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static void main(String[] args) {
        try (XDPPackageFilter program = BPFProgram.load(XDPPackageFilter.class)) {
            var cmd = new CommandLine(program);
            cmd.parseArgs(args);
            if (cmd.isUsageHelpRequested()) {
                cmd.usage(out);
                return;
            }
            program.run();
        }
    }
}
