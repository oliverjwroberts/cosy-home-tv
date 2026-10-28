# Cosy Home

A home screen for Android TV: a big clock over a small valley that follows the real sun. The
cottage lights come on at dusk, the moon and stars come out at night, and the weather sits under
the date. Press Down for every app and input on a frosted-glass panel.

![Cosy Home at night](docs/night.png)

| Dawn | Day | Dusk |
|---|---|---|
| ![Dawn](docs/dawn.png) | ![Day](docs/day.png) | ![Dusk](docs/dusk.png) |

![The apps panel by day](docs/apps-day.png)

## What it does

- **Follows the sun.** The TV works out sunrise and sunset for your town itself, without the internet.
  Dawn runs from 40 minutes before sunrise to 50 minutes after, and dusk from 50 minutes before
  sunset to 40 minutes after.
- **Shows the weather.** Temperature and conditions from [Open-Meteo](https://open-meteo.com),
  refreshed every 30 minutes.
- **Keeps six apps on the clock screen.** Down opens a panel with every app, an **Ambilight** and
  a **Settings** button, and the TV's inputs: Live TV, each HDMI port, and USB.
- **Looks after OLED panels.** The clock, the date and the dock move a few pixels every minute,
  so nothing stays in one place for hours.
- **Stays light.** On a Philips 55OLED707 it uses about 43 MB while another app is in front,
  where Google's launcher used 180 to 200 MB. It hands the scene's bitmaps back whenever it is
  out of sight.

On the remote:

| Key | On the clock screen | On the apps panel |
|---|---|---|
| Left, Right | Move along the dock | Move along a row |
| Down | Open the apps panel | Move down, then to the inputs |
| OK | Open the app | Open the app or input |
| Hold OK | Open the app's settings page | Open the app's settings page |
| Back | Does nothing, since this is Home | Back to the clock |
| Home | Back to the first dock app | Back to the clock |

## Install it

You need a computer on the same network as the TV, with `adb`, JDK 17, and the Android SDK
(platform 34 and build-tools 34). The TV needs Android TV 8 or later.

1. On the TV, turn on developer options: **Settings → Device Preferences → About**, then press
   OK on **Build** seven times.
2. Turn on **USB debugging** in **Developer options**. On most TVs this also opens debugging over
   the network, on port 5555.
3. Copy the settings file and set your town:

   ```sh
   cp cosy.defaults.properties cosy.properties
   ```

   See [Change the settings](#change-the-settings) for the keys.
4. Build and install, with your TV's IP address:

   ```sh
   TV_HOST=192.168.1.50 ./install.sh
   ```

   The first time, the TV asks whether to allow debugging. Accept it.

`install.sh` builds the app, installs it, then disables the stock launcher, because Android TV's
own launcher outranks every other Home app while it is enabled. It disables only a launcher it
knows (`com.google.android.tvlauncher`, `com.google.android.apps.tv.launcherx` or
`com.google.android.leanbacklauncher`), and it starts Cosy Home first so the TV is never without a
Home screen. If Home doesn't move to Cosy Home, it re-enables the stock launcher and stops.

If `adb` isn't on your `PATH`, pass it: `ADB=~/platform-tools/adb TV_HOST=… ./install.sh`.

For a lighter TV overall, debloat it first with
[cleanyourtv](https://github.com/Gathin23/cleanyourtv), which has tested lists for many models.

## Change the settings

`cosy.defaults.properties` holds every setting and its default. Put your own values in
`cosy.properties`, which git ignores, then run `./install.sh` again. A key you leave out keeps its
default. Find package names with `adb shell pm list packages`.

| Key | Default | What it does |
|---|---|---|
| `location.name` | empty | The town shown before the weather |
| `location.latitude`, `location.longitude` | empty | Where sunrise, sunset and the weather are worked out for. Without them there is no weather line, and the scene changes at fixed times: dawn 06:20, day 07:50, dusk 18:10, night 19:40 |
| `weather.temperature_unit` | `celsius` | The unit in the weather line: `celsius` or `fahrenheit`. Any other value means Celsius |
| `dock` | Netflix, Prime Video, Disney+, YouTube, Plex, Spotify | The apps on the clock screen, in order. Six fit. Cosy Home skips apps that aren't installed |
| `pinned` | empty | The apps next in the grid after the dock apps. The rest follow alphabetically |
| `hidden` | Philips system apps | Apps never shown as tiles |
| `live_tv_package` | `org.droidtv.playtv` | The app the **Live TV** input opens |
| `usb_package` | `org.droidtv.contentexplorer` | The app the **USB** input opens |
| `ambilight_action` | `org.droidtv.action.AMBILIGHT_OSD` | The screen the **Ambilight** button opens |

Lists are comma-separated. Cosy Home hides a button or input whose app isn't on the TV, so the
Philips defaults do no harm elsewhere.

To find your coordinates:

```sh
curl "https://geocoding-api.open-meteo.com/v1/search?name=Cardiff&count=1"
```

## Preview a time of day

```sh
adb shell am start -n com.cosyhome.tv/.HomeActivity --es phase dusk
```

The phases are `dawn`, `day`, `dusk` and `night`. `auto` follows the sun again.

## Go back to the stock launcher

Enable the stock launcher before you remove Cosy Home, or the TV is left without a Home screen:

```sh
adb shell pm enable com.google.android.tvlauncher
adb uninstall com.cosyhome.tv
```

On Google TV the launcher is `com.google.android.apps.tv.launcherx`.

## What it sends over the network

Cosy Home makes one request every 30 minutes, to `api.open-meteo.com`, with your latitude and
longitude rounded to two decimal places, about a kilometre. Open-Meteo also sees your IP address,
as any website does. Nothing else leaves the TV. There are no analytics and no accounts, and the
only permission is internet access. With no location set, Cosy Home makes no requests at all.

## Tested on

A Philips 55OLED707/12 on Android TV 11 (MediaTek MT5895). The Ambilight, Live TV and USB
defaults are for Philips sets. On other TVs, set `live_tv_package` and `usb_package` to your TV's
own apps.

Android 11 can't blur the screen live, so the glass panels draw a blurred copy of the scene.
Cosy Home makes the copy once each time the scene changes.

## Credits

Cosy Home started from the launcher in [cleanyourtv](https://github.com/Gathin23/cleanyourtv) by
Gathin, and keeps its Gradle setup, its way of loading app banners and its Outfit font. The scene,
the clock screen and the apps panel are new. Both projects are MIT-licensed. See `LICENSE`.

- [Outfit](https://github.com/Outfitio/Outfit-Fonts), by The Outfit Project Authors, under the
  SIL Open Font License 1.1 (`FONT-LICENSE-OFL.txt`).
- Weather data by [Open-Meteo.com](https://open-meteo.com), under CC BY 4.0.
