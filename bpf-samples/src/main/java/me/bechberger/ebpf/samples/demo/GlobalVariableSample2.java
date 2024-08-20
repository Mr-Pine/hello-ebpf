package me.bechberger.ebpf.samples.presentation;

import me.bechberger.ebpf.annotations.Unsigned;
import me.bechberger.ebpf.annotations.bpf.BPF;
import me.bechberger.ebpf.bpf.BPFProgram;
import me.bechberger.ebpf.bpf.GlobalVariable;
import me.bechberger.ebpf.runtime.OpenDefinitions.open_how;
import me.bechberger.ebpf.runtime.interfaces.SystemCallHooks;
import me.bechberger.ebpf.type.Ptr;

import static me.bechberger.ebpf.bpf.BPFJ.bpf_trace_printk;

/**
 * Global variable sample that counts the number of openat2 calls
 */
@BPF(license = "GPL")
public abstract class GlobalVariableSample2 extends BPFProgram implements SystemCallHooks {

    final GlobalVariable<@Unsigned Integer> counter = new GlobalVariable<>(0);

    @Override
    public void enterOpenat2(int dfd, String filename, Ptr<open_how> how) {
        counter.set(counter.get() + 1);
    }

    public static void main(String[] args) throws InterruptedException {
        try (GlobalVariableSample2 program = BPFProgram.load(GlobalVariableSample2.class)) {
            program.autoAttachPrograms();
            while (true) {
                System.out.println("OpenAt's: " + program.counter.get());
                Thread.sleep(1000);
            }
        }
    }
}