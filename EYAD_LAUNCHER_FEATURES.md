# EYAD Launcher Lite

A lightweight MojoLauncher-based Android Minecraft launcher profile focused on mobile play.

## Built-in features

- Three Bedrock-style touch presets:
  - Joystick & Tap — classic Bedrock-style movement
  - D-Pad & Tap — simple four-direction movement
  - Joystick & Aim — joystick movement with centered aim helper
- Built-in TAB button for the player list.
- Built-in chat, inventory, jump, sneak, sprint, attack, use, menu and perspective controls.
- Custom Controls remains available for full manual positioning.
- Built-in Mods page using Modrinth search.
- Install a Modrinth mod directly into the selected instance's `mods/` folder.
- Import a local `.jar` mod directly from Android storage.
- Existing Pojav/Mojo instance and runtime system is preserved.
- Mobile-friendly default resolution/RAM heuristics are preserved.

## GitHub Actions

The Android workflow also fetches the Mojo native components explicitly. This makes the repository buildable even if the Git repository was uploaded without Git submodule gitlinks.

Required native repositories:

- https://github.com/MojoLauncher/glfw
- https://github.com/MojoLauncher/MojoSDL
- https://github.com/MojoLauncher/mojoexec

The workflow builds Full Debug and No-runtime Debug APK artifacts.
