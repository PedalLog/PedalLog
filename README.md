# 🚴‍♂️ PedalLog 🚴‍♂️

<p>
  <img src="https://img.shields.io/badge/kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" />
  <a href="https://maplibre.org">
    <img src="https://img.shields.io/badge/maplibre-396CB2?style=for-the-badge&logo=maplibre&logoColor=white" />
  </a>
  <a href="https://github.com/PedalLog/PedalLog">
    <img src="https://img.shields.io/github/stars/PedalLog/PedalLog?style=for-the-badge&logo=github&logoColor=white" />
  </a>
</p>

> [!NOTE]
> This project is currently transitioning from Google Maps API to OpenStreetMap (MapLibre) and is a fork of [ishantchauhan710/BikeRush](https://github.com/ishantchauhan710/BikeRush).

PedalLog is a tracking and statistics management application designed for cyclists. It records your riding routes using real-time GPS tracking and provides visualized weekly/monthly statistics based on the stored data.

---

## 📸 Screenshots

| Tracking | Journey List | Statistics | Notifications |
|:---:|:---:|:---:|:---:|
| <img src="img/preview/.jpg" width="200"/> | <img src="img/preview/.jpg" width="200"/> | <img src="img/preview/.jpg" width="200"/> | <img src="img/preview/.jpg" width="200"/> |

---

## ✨ Key Features

- **Real-time Tracking**: Uses a Foreground Service to record accurate GPS paths and riding data even when the app is in the background.
- **Offline Map Support**: Loads high-performance maps without a network connection using MapLibre GL, MBTiles, and a local NanoHTTPD server.
- **Data Analysis & stats**: Calculates distance, time, calories burned, and average speed, providing visual statistics via WilliamChart.
- **Journey Management**: Securely stores and manages all riding data locally using Room Database.
- **Permission Handling**: Simple and secure location permission management with EasyPermissions.

---

## 🛠 Tech Stack

- **Language**: Kotlin
- **Architecture**: MVVM (Model-View-ViewModel) with Clean Architecture principles
- **Dependency Injection**: Hilt
- **Database**: Room Persistence Library
- **UI Components**: Navigation Component, View Binding, Material Design
- **Map**: MapLibre GL Android SDK, MBTiles
- **Asynchronous**: Coroutines & Flow
- **Logging**: Timber
- **Image Loading**: Glide
- **Charts**: WilliamChart

---

## 📧 Contact

For any queries, please feel free to email at [PedalLog@hotmail.com](mailto:PedalLog@hotmail.com).
