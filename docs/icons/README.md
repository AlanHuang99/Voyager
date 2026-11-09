# Voyager icon for Lawnicons

[voyager.svg](voyager.svg) is an outlined, monochrome version of Voyager's boat icon for an icon-pack contribution. It uses a 192 × 192 canvas, black 12-pixel strokes, rounded caps and joins, and no fill. It is licensed under the repository's [GPLv3 license](../../LICENSE).

Voyager's release application component is `com.voyagerfiles/com.voyagerfiles.app.MainActivity`. In a Lawnicons contribution, copy the SVG to `svgs/voyager.svg` and add this component mapping to `app/assets/appfilter.xml`:

```xml
<item component="ComponentInfo{com.voyagerfiles/com.voyagerfiles.app.MainActivity}" drawable="voyager" name="Voyager" />
```

Follow [Lawnicons' contribution guide](https://github.com/LawnchairLauncher/lawnicons/blob/develop/CONTRIBUTING.md#contributing-icons) and [design guidelines](https://github.com/LawnchairLauncher/lawnicons/blob/develop/docs/design_guidelines.md). Inclusion in Lawnicons follows its own review and release process.
