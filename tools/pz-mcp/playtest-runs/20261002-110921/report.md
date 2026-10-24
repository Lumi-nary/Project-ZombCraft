# Playtest: collision

16 steps in 42 s, actions chosen by decision model. 2 anomalies (2 triaged as bugs).

## Anomalies

- step 2 `mine` [bug 0.825]: mining a block in reach did not reduce the block count (may have aimed at PZ geometry)
  - screenshot: C:\Users\Maple\Desktop\Pz x Minecraft\tools\pz-mcp\playtest-runs\20261002-110921\anomaly-002.png
- step 11 `mine` [bug 0.857]: mining a block in reach did not reduce the block count (may have aimed at PZ geometry)
  - screenshot: C:\Users\Maple\Desktop\Pz x Minecraft\tools\pz-mcp\playtest-runs\20261002-110921\anomaly-011.png

## Steps

| # | action | result |
|---|---|---|
| 1 | walk_forward | ok |
| 2 | mine | mining a block in reach did not reduce the block count (may have aimed at PZ geometry) |
| 3 | strafe_right | ok |
| 4 | strafe_left | ok |
| 5 | jump_forward | ok |
| 6 | look_down | ok |
| 7 | place_block | ok |
| 8 | turn_left | ok |
| 9 | walk_forward | ok |
| 10 | walk_forward | ok |
| 11 | mine | mining a block in reach did not reduce the block count (may have aimed at PZ geometry) |
| 12 | turn_right | ok |
| 13 | place_block | ok |
| 14 | walk_forward | ok |
| 15 | turn_left | ok |
| 16 | attack | ok |
