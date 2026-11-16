# Before/after renders

Render the same `look` shots from two checkouts, then diff them. Both sides leave the repository unchanged.

## 1. Choose the shots

Take coordinates from the level file (`startTiles`, `namedPoints`, prefab positions), the room code, or `dungeon-play <instance> state`. Every run uses the same shot list.

## 2. Render both sides

Render the old state from its own worktree, so the working tree stays as it is: `git worktree add "$env:TEMP\<room>-before" <ref>`. Run this in each checkout, with a different output directory:

```powershell
dungeon-play start runMushRoom                  # or: mp run<Room> - for rooms with a main menu
dungeon-play <instance> look "$env:TEMP\cap\after" start:14,12,0.7 cellar:106,48,1.2
dungeon-play <instance> stop
```

Bring both games to the same state before `look`: the view shows the live game, so the hero, enemies and opened doors appear as they stand. The two games may run at the same time, each as its own instance.

Done when both directories hold a PNG for every shot.

## 3. Compare

```powershell
dungeon-play compare "$env:TEMP\cap\before" "$env:TEMP\cap\after" "$env:TEMP\cap\diff"
```

It prints the changed pixels per shot and writes `<name>.png` (before | after) and `<name>_diff.png` (changed pixels in red). Inspect every image with changed pixels. Noise between runs: flames and other animated sprites, water and lava shaders, and the single red dots of ambient particles. Anything else is a real change.

Done when each changed region is explained as noise or reported as a visual difference.

## 4. Clean up and show

Remove the before worktree (`git worktree remove --force <path>`). Show the user the side-by-side images as Markdown images with absolute paths.
