package me.bechberger.ebpf.samples;

import me.bechberger.ebpf.annotations.Size;
import me.bechberger.ebpf.annotations.Unsigned;
import me.bechberger.ebpf.annotations.bpf.BPF;
import me.bechberger.ebpf.annotations.bpf.BPFFunction;
import me.bechberger.ebpf.annotations.bpf.MethodIsBPFRelatedFunction;
import me.bechberger.ebpf.bpf.BPFJ;
import me.bechberger.ebpf.bpf.BPFProgram;
import me.bechberger.ebpf.bpf.GlobalVariable;
import me.bechberger.ebpf.runtime.OpenDefinitions.open_how;
import me.bechberger.ebpf.runtime.helpers.BPFHelpers;
import me.bechberger.ebpf.runtime.interfaces.SystemCallHooks;
import me.bechberger.ebpf.type.Ptr;

import static me.bechberger.ebpf.bpf.BPFJ.bpf_trace_printk;
import static me.bechberger.ebpf.bpf.BPFJ.sizeof;

/**
 * Hello, World from BPF
 */
@BPF(license = "GPL")
public abstract class HelloWorld extends BPFProgram implements SystemCallHooks {

    @Override
    public void enterOpenat2(int dfd, String filename, Ptr<open_how> how) {
        bpf_trace_printk("Hello, World!");
    }

    public static void main(String[] args) {
        try (HelloWorld program = BPFProgram.load(HelloWorld.class)) {
            program.autoAttachPrograms();
            program.tracePrintLoop(f -> String.format("%d: %s: %s", (int)f.ts(), f.task(), f.msg()));
        }
    }
}