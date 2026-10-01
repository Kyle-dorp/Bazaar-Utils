import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.io.*;
import java.nio.file.*;
import java.util.zip.*;

/** Patches BazaarFlipMod so the min-margin floor goes through BaseProfit.minMargin(buyPrice, configured). */
public class Patch {
    static final String CLS = "uwu/ramona/bazaar/BazaarFlipMod.class";

    public static void main(String[] a) throws Exception {
        Path in = Path.of(a[0]), out = Path.of(a[1]);
        int patched = 0, taxed = 0, qtyPatched = 0;
        try (ZipFile zf = new ZipFile(in.toFile());
             ZipOutputStream zo = new ZipOutputStream(Files.newOutputStream(out))) {
            var en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                byte[] data = zf.getInputStream(e).readAllBytes();
                if (e.getName().equals(CLS)) {
                    ClassNode cn = new ClassNode();
                    new ClassReader(data).accept(cn, 0);
                    for (MethodNode m : cn.methods) {
                        // apply sell tax to the sell price in the profit and total-sell-value calculations
                        for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
                            if (n instanceof VarInsnNode v && v.getOpcode() == Opcodes.DLOAD && v.var == 16) {
                                AbstractInsnNode nx = v.getNext();
                                boolean profit = nx instanceof VarInsnNode v2 && v2.getOpcode() == Opcodes.DLOAD && v2.var == 14
                                        && nx.getNext().getOpcode() == Opcodes.DSUB;
                                boolean total = nx instanceof LdcInsnNode l && l.cst instanceof Double d && d == 71680.0;
                                if (profit || total) {
                                    m.instructions.insert(v, new MethodInsnNode(Opcodes.INVOKESTATIC,
                                            "com/github/mkram17/bazaarutils/features/BaseProfit", "afterTax", "(D)D", false));
                                    taxed++;
                                }
                            }
                        }
                        // size the order quantity to the spend limit instead of a fixed full inventory
                        for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
                            if (n instanceof LdcInsnNode l && l.cst instanceof Double d && d == 71680.0) {
                                InsnList repl = new InsnList();
                                repl.add(new VarInsnNode(Opcodes.DLOAD, 14));
                                repl.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                        "com/github/mkram17/bazaarutils/features/BaseProfit", "qty", "(D)D", false));
                                AbstractInsnNode next = n.getNext();
                                m.instructions.insert(n, repl);
                                m.instructions.remove(n);
                                qtyPatched++;
                                n = next.getPrevious();
                            }
                        }
                        for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
                            if (n instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETSTATIC
                                    && f.name.equals("backgroundAlertThreshold")) {
                                // stack before: [margin]; push buyPrice (local 14) then configured floor, call helper
                                m.instructions.insertBefore(f, new VarInsnNode(Opcodes.DLOAD, 14));
                                AbstractInsnNode i2d = f.getNext();
                                m.instructions.insert(i2d, new MethodInsnNode(Opcodes.INVOKESTATIC,
                                        "com/github/mkram17/bazaarutils/features/BaseProfit", "minMargin", "(DD)D", false));
                                patched++;
                                n = i2d.getNext();
                            }
                        }
                    }
                    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                    cn.accept(cw);
                    data = cw.toByteArray();
                }
                zo.putNextEntry(new ZipEntry(e.getName()));
                zo.write(data);
                zo.closeEntry();
            }
        }
        System.out.println("patched sites: " + patched + ", taxed: " + taxed + ", qty: " + qtyPatched);
    }
}
