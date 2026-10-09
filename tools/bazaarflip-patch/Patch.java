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
        int patched = 0, taxed = 0, qtyPatched = 0, daemon = 0, perHour = 0, swapped = 0, labels = 0, rateGate = 0, avgGate = 0, screens = 0;
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
                        // make the scheduler threads daemon so the JVM can exit when the game closes
                        for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
                            if (n instanceof MethodInsnNode mi && mi.owner.equals("java/util/concurrent/Executors")
                                    && mi.name.equals("newScheduledThreadPool") && mi.desc.equals("(I)Ljava/util/concurrent/ScheduledExecutorService;")) {
                                mi.owner = "com/github/mkram17/bazaarutils/features/BaseProfit";
                                mi.name = "daemonScheduler";
                                daemon++;
                            }
                        }
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
                        // totalProfit (local 31) becomes profit per hour: profit/item * min(7d buy, 7d sell) / 168
                        for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
                            if (n instanceof VarInsnNode v && v.getOpcode() == Opcodes.DSTORE && v.var == 31
                                    && v.getPrevious().getOpcode() == Opcodes.DSUB) {
                                InsnList repl = new InsnList();
                                repl.add(new VarInsnNode(Opcodes.ALOAD, 18)); // quick_status JsonObject
                                repl.add(new VarInsnNode(Opcodes.DLOAD, 23)); // profit per item (after tax)
                                repl.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                        "com/github/mkram17/bazaarutils/features/BaseProfit", "profitPerHour",
                                        "(Lcom/google/gson/JsonObject;D)D", false));
                                repl.add(new VarInsnNode(Opcodes.DSTORE, 31));
                                m.instructions.insert(v, repl);
                                perHour++;
                                break;
                            }
                        }
                        // reject flips below the minimum items per hour, right after the max-margin check
                        for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
                            if (n instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETSTATIC
                                    && f.name.equals("backgroundAlertMaxThreshold")) {
                                AbstractInsnNode j = f.getNext();
                                while (j != null && !(j instanceof JumpInsnNode)) j = j.getNext();
                                JumpInsnNode jump = (JumpInsnNode) j;
                                InsnList repl = new InsnList();
                                repl.add(new VarInsnNode(Opcodes.ALOAD, 18));
                                repl.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                        "com/github/mkram17/bazaarutils/features/BaseProfit", "passesRate",
                                        "(Lcom/google/gson/JsonObject;)Z", false));
                                repl.add(new JumpInsnNode(Opcodes.IFEQ, jump.label));
                                m.instructions.insert(jump, repl);
                                rateGate++;
                                break;
                            }
                        }
                        // reject flips whose prices are far from the 7-day average (bought-out / dumped items)
                        for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
                            if (n instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETSTATIC
                                    && f.name.equals("backgroundAlertMaxThreshold")) {
                                AbstractInsnNode j = f.getNext();
                                while (j != null && !(j instanceof JumpInsnNode)) j = j.getNext();
                                JumpInsnNode jump = (JumpInsnNode) j;
                                InsnList repl = new InsnList();
                                repl.add(new VarInsnNode(Opcodes.ALOAD, 10)); // product id
                                repl.add(new VarInsnNode(Opcodes.DLOAD, 14)); // buy price (top buy order)
                                repl.add(new VarInsnNode(Opcodes.DLOAD, 16)); // sell price (lowest sell offer)
                                repl.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                        "com/github/mkram17/bazaarutils/features/BaseProfit", "passesAverage",
                                        "(Ljava/lang/String;DD)Z", false));
                                repl.add(new JumpInsnNode(Opcodes.IFEQ, jump.label));
                                m.instructions.insert(jump, repl);
                                avgGate++;
                                break;
                            }
                        }
                        // open our compact settings screen instead of the stock one
                        for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
                            if (n instanceof TypeInsnNode t && t.getOpcode() == Opcodes.NEW
                                    && t.desc.equals("uwu/ramona/bazaar/config/BazaarConfigScreen")) {
                                t.desc = "com/github/mkram17/bazaarutils/features/FlipSettingsScreen";
                                screens++;
                            } else if (n instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKESPECIAL
                                    && mi.owner.equals("uwu/ramona/bazaar/config/BazaarConfigScreen") && mi.name.equals("<init>")) {
                                mi.owner = "com/github/mkram17/bazaarutils/features/FlipSettingsScreen";
                            }
                        }
                        // sort orders: the first list ranks by profit/hour, the second by margin %
                        if (m.name.startsWith("lambda$perform")) {
                            for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
                                if (n instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETFIELD
                                        && f.owner.equals("uwu/ramona/bazaar/BazaarFlipMod$BazaarItem")) {
                                    if (f.name.equals("profitMargin")) { f.name = "totalProfit"; swapped++; }
                                    else if (f.name.equals("totalProfit")) { f.name = "profitMargin"; swapped++; }
                                }
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
                if (e.getName().equals("uwu/ramona/bazaar/hud/HudRenderer.class")) {
                    ClassNode cn = new ClassNode();
                    new ClassReader(data).accept(cn, 0);
                    for (MethodNode m : cn.methods) {
                        for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
                            if (n instanceof LdcInsnNode l && l.cst instanceof String s) {
                                String t = s;
                                if (t.contains("Bazaar Flips (Profit %)")) t = t.replace("(Profit %)", "(Profit/hr)");
                                else if (t.contains("Bazaar Flips (Total Profit)")) t = t.replace("(Total Profit)", "(Profit %)");
                                else if (t.contains("Total Profit: ")) t = t.replace("Total Profit: ", "Profit/hr: ");
                                if (!t.equals(s)) { l.cst = t; labels++; }
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
        System.out.println("patched sites: " + patched + ", taxed: " + taxed + ", qty: " + qtyPatched + ", daemon: " + daemon + ", perHour: " + perHour + ", swapped: " + swapped + ", labels: " + labels + ", rateGate: " + rateGate + ", avgGate: " + avgGate + ", screens: " + screens);
    }
}
