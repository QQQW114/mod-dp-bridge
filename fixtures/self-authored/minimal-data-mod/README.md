# Minimal data Mod fixture

Source: self-authored for `mod-dp-bridge` regression testing.
License: CC0-1.0 (`LICENSE.txt`).

Coverage:

- one item;
- one wall block with requirements referencing that item;
- one flying unit with a weapon and `BasicBulletType`;
- one 1x1 RGBA PNG for each content entry plus `icon.png`;
- English and Simplified Chinese bundles.

The fixture deliberately uses a source-style `assets/` directory so the input
scanner must normalize Mod source layout before producing a Data Assets root.
