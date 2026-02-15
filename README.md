<h1 align="center">PedalLog</h1>
  
<p align="center">
  <img src="img/logo/PedalLogo_Background.png" alt="PedalLog" width="160" />
</p>

<p align="center">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" />
  <a href="https://maplibre.org">
    <img alt="MapLibre" src="https://img.shields.io/badge/MapLibre-396CB2?style=for-the-badge&logo=maplibre&logoColor=white" />
  </a>
  <a href="https://github.com/PedalLog/PedalLog">
    <img alt="GitHub stars" src="https://img.shields.io/github/stars/PedalLog/PedalLog?style=for-the-badge&logo=github&logoColor=white" />
  </a>
</p>

PedalLog is an Android tracking and stats app for cyclists. It records your rides with real-time GPS tracking and turns them into weekly/monthly insights.

> [!NOTE]
> This project is a fork of [ishantchauhan710/BikeRush](https://github.com/ishantchauhan710/BikeRush).

---

## Screenshots

| Tracking | Floating Bar | Statistics | Save & Keep | Settings |
|:---:|:---:|:---:|:---:|:---:|
| <img src="img/preview/recording-page.jpg" width="200" alt="Tracking"/> | <img src="img/preview/floating-bar.jpg" width="200" alt="Floating Bar"/> | <img src="img/preview/analysis.jpg" width="200" alt="Statistics"/> | <img src="img/preview/recordings.jpg" width="200" alt="Recordings"/> | <img src="img/preview/settings.jpg" width="200" alt="Settings"/> |

---

## Key Features

- **Real-time tracking**: Uses a foreground service for accurate GPS paths and ride metrics, even in the background.
- **Offline maps**: Renders maps without a network connection using MapLibre, MBTiles, and a lightweight local HTTP server.
- **Ride analytics**: Calculates distance, time, calories, and average speed, and visualizes trends with charts.
- **Journey management**: Stores rides locally with Room and provides an interface to browse and manage them.
- **Permissions**: Simple and safe location permission handling with EasyPermissions.

---

## Tech Stack

- **Language**: Kotlin
- **Architecture**: MVVM with Clean Architecture principles
- **Dependency Injection**: Hilt
- **Database**: Room
- **UI**: Navigation Component, View Binding, Material Design
- **Maps**: MapLibre GL Android SDK, MBTiles
- **Async**: Coroutines & Flow
- **Logging**: Timber
- **Images**: Glide
- **Charts**: WilliamChart

---

## Getting Started

### Prerequisites

- Android Studio (latest stable)
- Android SDK installed via Android Studio

### Build & Run

1. Clone the repository.
2. Open the project in Android Studio.
3. Let Gradle sync.
4. Run the `app` configuration on a device or emulator.

You can also build from the command line:

- Windows (PowerShell): `./gradlew.bat assembleDebug`
- macOS/Linux: `./gradlew assembleDebug`

---

## Offline Map (MBTiles)

PedalLog supports offline maps via `.mbtiles`.

- Select an MBTiles file from **Settings**.
- The app will load tiles through a local server and render them in MapLibre.

---

## Contact

Questions or feedback: [PedalLog@hotmail.com](mailto:PedalLog@hotmail.com)
