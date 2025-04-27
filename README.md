# Network Cell Analyzer

A comprehensive network analysis tool that collects, analyzes, and visualizes cellular network data from mobile devices. This project consists of a web dashboard and an Android application for real-time network monitoring and analysis.

## 🌟 Features

### Web Dashboard

- Real-time network statistics and visualizations
- User-specific analytics and history
- Network type distribution analysis (2G/3G/4G/5G/WiFi)
- Signal strength and SNR monitoring
- Device and operator statistics
- Interactive time-based filtering
- User management system

### Android App

- Real-time network data collection
- Automatic data upload to server
- Support for both authenticated and guest users
- Background service for continuous monitoring
- Device information collection
- Network type detection
- Signal strength measurement

## 🛠️ Technical Stack

### Backend

- **Framework**: Flask (Python)
- **Database**: PostgreSQL
- **ORM**: SQLAlchemy
- **Authentication**: Werkzeug Security
- **API**: RESTful endpoints
- **Data Processing**: PostgreSQL-specific optimizations

### Frontend (Web Dashboard)

- **Framework**: HTML5, CSS3, JavaScript
- **Charts**: Chart.js
- **UI Components**: Tailwind CSS
- **Real-time Updates**: Fetch API

### Android App

- **Language**: Kotlin
- **Architecture**: MVVM
- **Networking**: Retrofit
- **Database**: Room
- **Background Processing**: WorkManager
- **UI**: Material Design

## 📊 Data Collection

The system collects various network metrics:

- Signal Power (dBm)
- Signal-to-Noise Ratio (SNR)
- Network Type (2G/3G/4G/5G)
- Operator Information
- Frequency Band
- Cell ID
- Device Information
- Location Data (when available)

## 🚀 Getting Started

### Prerequisites

- Python 3.8+
- PostgreSQL 12+
- Node.js 14+ (for development)
- Android Studio (for app development)
- JDK 11+

### Backend Setup

1. Clone the repository:
   ```bash
   git clone https://github.com/Ali-Rammal1/NetworkCellAnalyzer
   cd network-cell-analyzer
   ```
2. Install dependencies:
   ```bash
   pip install -r requirements.txt
   ```
3. Set up environment variables:
   Create a `.env` file in the root directory:
   ```
   DATABASE_URL=postgresql://postgres.sqregetluaqenetlfioo:[SECRET_KEY]@aws-0-eu-central-1.pooler.supabase.com:6543/postgres
   SECRET_KEY= Can be given upon request (its the connection to the database)
   ```
4. Start the server:
   ```bash
   python server.py
   ```

### Android App Setup

1. Open the `android` directory in Android Studio
2. Sync the project with Gradle files
3. Modify `gradle.properties`: Update the `API_BASE_URL` to the IP address of your server computer
4. Build and run the app
5. Make sure your phone and the server are connected to the same Wi-Fi network

## 📡 API Endpoints

### Authentication

- `POST /register` - User registration
- `POST /login` - User authentication

### Data Collection

- `POST /upload` - Upload network data from mobile devices

### Analytics

- `GET /api/stats` - Get overall statistics
- `GET /api/user-stats` - Get user-specific statistics
- `GET /api/server-user-stats` - Get detailed user statistics
- `GET /api/all-users` - Get list of all users

## 📱 Mobile App Features

### Data Collection

- Automatic background monitoring
- Network type detection
- Signal strength measurement
- Device information collection
- Periodic data upload

### User Interface

- Real-time network status
- Historical data view
- User authentication
- Settings configuration
- Data upload status

## 🔒 Security Features

- Token-based authentication with 64-character tokens valid for 30 days
- Password requirements:
  - Minimum 6 characters
  - At least one uppercase letter
  - At least one lowercase letter
  - At least one digit
  - At least one special character
  - No spaces
- Guest mode for data uploads without authentication
- Comprehensive error handling
- Environment variable configuration
- Input validation
- Secure API endpoints

## 📈 Data Analysis

The system provides various analytics:

- Network type distribution
- Signal strength trends
- Operator statistics
- Device statistics
- User activity patterns
- Historical data analysis

## 📝 License

This project is licensed under the MIT License - see the LICENSE file for details.
