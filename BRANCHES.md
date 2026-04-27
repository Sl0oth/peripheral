# Peripheral — Branch Strategy

Peripheral ships four JARs targeting different Minecraft version ranges. Each lives on its own branch/worktree because the MC API surface is incompatible across these ranges — they cannot share a single build.

---

## The Four Branches

| Branch | Worktree | MC range | Mapping system | Mouse/keyboard API |
|--------|----------|----------|----------------|--------------------|
| `main` | (root) | 26.1.x | Mojmap | `Click` record + `KeyInput`/`CharInput` |
| `mc/1.21.4` (`sharp-hertz`) | `.claude/worktrees/sharp-hertz` | 1.21.9 – 1.21.11 | Yarn | `Click` record + `KeyInput`/`CharInput` |
| `mc/1.21.8` | `.claude/worktrees/mc-1-21-8` | 1.21.5 – 1.21.8 | Yarn | Old `(double, double, int)` + `(int, int, int)` |
| *(detached, mc-1-21-4-orig)* | `.claude/worktrees/mc-1-21-4-orig` | 1.21.0 – 1.21.4 | Yarn | Old `(double, double, int)` + old `ClickEvent` |

---

## API Differences Between Branches

### Mouse events (`mouseClicked`, `mouseDragged`, `mouseReleased`)

**main / sharp-hertz (1.21.9+):**
```java
public boolean mouseClicked(Click click, boolean focused)
public boolean mouseDragged(Click click, double dx, double dy)
public boolean mouseReleased(Click click)
// Access: click.x(), click.y(), click.button()
```

**mc/1.21.8 / mc-1-21-4-orig (≤ 1.21.8):**
```java
public boolean mouseClicked(double mouseX, double mouseY, int button)
public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy)
public boolean mouseReleased(double mouseX, double mouseY, int button)
```

### Keyboard events (`keyPressed`, `charTyped`)

**main / sharp-hertz (1.21.9+):**
```java
public boolean keyPressed(KeyInput input)   // input.key(), input.hasShift(), input.hasCtrl()
public boolean charTyped(CharInput input)   // input.codepoint()
```

**mc/1.21.8 / mc-1-21-4-orig (≤ 1.21.8):**
```java
public boolean keyPressed(int keyCode, int scanCode, int modifiers)
public boolean charTyped(char chr, int modifiers)
```

### `ClickEvent` / `HoverEvent`

**main / sharp-hertz / mc/1.21.8 (1.21.5+):** Abstract interface — use subclasses:
```java
new ClickEvent.RunCommand("/cmd")
new ClickEvent.OpenUrl(URI.create(url))
new ClickEvent.SuggestCommand("/cmd")
new ClickEvent.CopyToClipboard(text)
new HoverEvent.ShowText(Text.literal("..."))
```

**mc-1-21-4-orig (≤ 1.21.4):** Concrete class — use constructor:
```java
new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/cmd")
new ClickEvent(ClickEvent.Action.OPEN_URL, url)
new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("..."))
```

### `PlayerInventory` main stacks

**main / sharp-hertz / mc/1.21.8 (1.21.5+):** `inv.getMainStacks()`

**mc-1-21-4-orig (≤ 1.21.4):** `inv.main` (public field)

### Key binding registration

**main (Mojmap):**
```java
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
KeyMappingHelper.registerKeyMapping(new KeyMapping("key.id", GLFW_KEY_G, KeyMapping.Category.GAMEPLAY))
```

**sharp-hertz / mc/1.21.8 / mc-1-21-4-orig (Yarn):**
```java
import net.minecraft.client.option.KeyBinding;
KeyBindingHelper.registerKeyBinding(new KeyBinding("key.id", GLFW.GLFW_KEY_G, KeyBinding.Category.GAMEPLAY))
```

### `StyledButton` / `ClickableWidget`

**main / sharp-hertz (1.21.9+):** `StyledButton extends ClickableWidget`, overrides `onClick(Click, boolean)` — present in these branches only. Tab files use `ClickableWidget` typed fields instead of `ButtonWidget`.

**mc/1.21.8 / mc-1-21-4-orig:** No `StyledButton` — use `ButtonWidget.builder(...)` directly.

---

## Rule: Porting New Features

**Every feature added to one branch must be ported to all four.** The following files almost always need branch-specific versions:

| File | What differs |
|------|-------------|
| `PeripheralScreen.java` | `mouseClicked` / `mouseDragged` / `mouseReleased` signatures |
| `ScriptEditorScreen.java` | `keyPressed` / `charTyped` / mouse signatures |
| `BuildPickerScreen.java` | `mouseClicked` signature |
| `PeripheralClient.java` | `KeyMapping` vs `KeyBinding`; `ClickEvent` constructor |
| `PeripheralHttpServer.java` | `ClickEvent`/`HoverEvent` constructors; `inv.main` vs `getMainStacks()` |
| `GameStateReader.java` | `inv.main` vs `getMainStacks()` |
| `gradle.properties` | `minecraft_version`, `yarn_mappings`, `fabric_api_version`, `modmenu_version` |
| `fabric.mod.json` | `minecraft` version constraint |

Files that are **identical across all branches** (copy freely):
- `PeripheralConfig.java`, `ScriptRunner.java`, `PeripheralHud.java`, `PeripheralOverlay.java`
- `BlockTracker.java`, `PeripheralNavigator.java`, `PeripheralStateTracker.java`
- `ChatLog.java`, `BuildSession.java`, `GameStateReader.java` (except inventory lines)
- All Python example scripts and resources

---

## Building All Four JARs

```bash
# 1.21.0 – 1.21.4
cd /Users/oliverhead/Documents/Dev/peripheral-26.1/.claude/worktrees/mc-1-21-4-orig && ./gradlew build

# 1.21.5 – 1.21.8
cd /Users/oliverhead/Documents/Dev/peripheral-26.1/.claude/worktrees/mc-1-21-8 && ./gradlew build

# 1.21.9 – 1.21.11
cd /Users/oliverhead/Documents/Dev/peripheral-26.1/.claude/worktrees/sharp-hertz && ./gradlew build

# 26.1.x
cd /Users/oliverhead/Documents/Dev/peripheral-26.1 && ./gradlew build
```

Output JAR in each case: `build/libs/peripheral-<version>.jar`
