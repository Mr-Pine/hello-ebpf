package me.bechberger.ebpf.samples.demo;

import me.bechberger.ebpf.annotations.bpf.BPF;
import me.bechberger.ebpf.bpf.*;

import me.bechberger.ebpf.samples.BasePacketParser;
import me.bechberger.ebpf.type.Ptr;

import static me.bechberger.ebpf.runtime.XdpDefinitions.*;


/**
 * Block incoming packets from or to the HTTP port
 */
@BPF(license = "GPL")
public abstract class BlockHTTP extends BPFProgram implements XDPHook, BasePacketParser {

    @Override
    public xdp_action xdpHandlePacket(Ptr<xdp_md> packet) {
        PacketInfo info = new PacketInfo();
        if (parsePacket2(packet.val().data, packet.val().data_end, Ptr.of(info))) {
            if (info.sourcePort == HTTP_PORT) {
                BPFJ.bpf_trace_printk("Dropping http packet");
                return xdp_action.XDP_DROP;
            }
        }
        return xdp_action.XDP_PASS;
    }

    public static void main(String[] args) throws InterruptedException {
        try (BlockHTTP program = BPFProgram.load(BlockHTTP.class)) {
            program.xdpAttach();
            program.tracePrintLoop();
        }
    }
}
