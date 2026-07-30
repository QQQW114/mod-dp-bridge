# Negative fixtures

Source: self-authored for `mod-dp-bridge` regression testing.
License: CC0-1.0.

Expected results:

| Fixture | Expected diagnostic |
| --- | --- |
| `zip-slip-relative.zip` | Reject the archive before extraction because an entry escapes the destination directory. |
| `missing-resource-mod/` | Finish inventory/conversion planning, then report missing base sprites, explicit bullet sprite, and custom sound without silently succeeding. |
| `malformed-old-cp.hjson` | Report an HJSON parse failure with the source path and location. |

No negative fixture may create a file outside the per-test temporary directory.
