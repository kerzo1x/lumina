# Modrinth page

Everything to paste into the Modrinth project form.

| Field | Value |
| --- | --- |
| Name | Lumina |
| Summary | Shaders for VulkanMod: sun shadows, warm light, water, a new sky and bloom. No shader packs, light enough for PvP. |
| Project type | Mod |
| Categories | Decoration (primary), Utility, Optimization |
| Environment | Client-side only (client: required, server: unsupported) |
| Loader / game version | Fabric, 1.21.11 |
| License | MIT (`MIT License`) |
| Source code | https://github.com/kerzo1x/vulkan-shaders |
| Issue tracker | https://github.com/kerzo1x/vulkan-shaders/issues |
| Icon | `docs/icon.png` (512×512) |
| Gallery | PNG originals in `docs/screenshots/png/`, or the JPEG copies in `docs/screenshots/` |
| Dependencies | VulkanMod: required, Fabric API: required, Mod Menu: optional |

## Description

Copy everything below this line into the description field.

---

![Lumina](https://raw.githubusercontent.com/kerzo1x/vulkan-shaders/main/docs/screenshots/01_ice_duel.jpg)

**Lumina** is a shader mod for [VulkanMod](https://modrinth.com/mod/vulkanmod). It adds its own lightweight shader pipeline on top of VulkanMod's renderer: sun shadows, warm lighting, water, a new sky, bloom and colour grading. No shader packs are needed. Everything is on out of the box and tuned from one settings screen, and it is light enough to keep on in PvP.

| Shaders off | Shaders on |
| --- | --- |
| ![](https://raw.githubusercontent.com/kerzo1x/vulkan-shaders/main/docs/screenshots/13_shaders_off.jpg) | ![](https://raw.githubusercontent.com/kerzo1x/vulkan-shaders/main/docs/screenshots/13_shaders_on.jpg) |

## Features

**Shadows**
- Sun and moon shadows for terrain, players and mobs, with adjustable quality (1024 / 2048 / 4096), distance, darkness and softness
- Contact shadows for small details the shadow map is too coarse for
- Screen-space ambient occlusion in corners and crevices
- Players, mobs, block entities and the hand are lit and shadowed like the terrain

**Light and colour**
- Warm sunlight with separate sun and ambient brightness
- Bloom and sun rays
- Colour grading: warmth, saturation, contrast, brightness, vignette, sharpness

**Water and reflections**
- Rippling water with adjustable wave height
- Reflections on ice and stained glass
- Puddles that appear on the ground in the rain
- Lava that glows on its own and churns slowly

**Sky**
- Overworld sky with atmosphere, a round sun, a moon with phases and stars
- Soft clouds with adjustable cover
- A separate sky for the End

**World**
- Waving grass, plants and leaves

**Performance**
- Crowd optimisation: when a lot of creatures are around, only the nearest ones are drawn (the limit is adjustable)
- Every effect can be switched off separately

## Requirements

- Minecraft 1.21.11, Fabric Loader 0.17.0 or newer
- [VulkanMod](https://modrinth.com/mod/vulkanmod) 0.6.8 or newer
- [Fabric API](https://modrinth.com/mod/fabric-api)
- Optional: [Mod Menu](https://modrinth.com/mod/modmenu), opens the settings from the mod list

Client-side only. Not compatible with Beryl.

## Controls

- `F10` toggles the shaders
- `F9` opens the settings

Both keys can be rebound. Settings are stored in `config/lumina.json`. The mod is translated into English and Russian.

## Something went wrong?

If rendering fails, Lumina switches itself off instead of crashing the game and writes the reason to `logs/lumina.log`. Press `F10` to try again, and attach that file to your [issue](https://github.com/kerzo1x/vulkan-shaders/issues).

## License

MIT. Do whatever you want with the code, just mention **kerzo1x** as the original author.
