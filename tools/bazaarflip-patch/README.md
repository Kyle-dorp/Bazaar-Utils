# BazaarFlip patcher

Small ASM script that patches your own copy of [BazaarFlip](https://modrinth.com/mod/bazaarflip) (by SertraFurr, MIT) so its
flip list uses the Bazaar Utils features in this fork:

- **Base profit**: a minimum profit in coins per item (`/flipprofit <coins>` or the new box in BazaarFlip's config screen).
  The fixed minimum % margin becomes whatever % each item needs to earn that many coins.
- **Sell tax**: profit, margin and totals use the price you receive after the Bazaar sell tax (`/fliptax <percent>`, default 1.25).
- **Spend-limit sizing**: with a base profit set, the order quantity is sized to Max Spend Limit instead of a fixed 71,680 items,
  so expensive items are not rejected for not affording a full inventory.

The BazaarFlip jar is **not** included here. The patched jar needs this fork of Bazaar Utils installed (the patch calls
`BaseProfit` from it), and was written against BazaarFlip 1.0.7 for Minecraft 26.2.

## Use

Needs `asm` and `asm-tree` 9.x jars on the classpath (Gradle caches contain them).

```
javac -cp asm-9.10.1.jar;asm-tree-9.10.1.jar Patch.java
java  -cp .;asm-9.10.1.jar;asm-tree-9.10.1.jar Patch BazaarFlip-26.2-1.0.7.jar BazaarFlip-patched.jar
```

Keep your original jar, then replace it in `mods` with the patched one. It reports how many sites it changed
(`patched sites: 2, taxed: 4, qty: 4` for 1.0.7). Any other number means the BazaarFlip version differs and the patch is not valid for it.
