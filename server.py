import os
from datetime import datetime, timedelta, timezone
from flask import Flask, request, jsonify, render_template
from flask_sqlalchemy import SQLAlchemy
from sqlalchemy.sql import text
from sqlalchemy import desc, func, distinct, cast, Float
from dotenv import load_dotenv
import traceback
import webbrowser
import threading

from werkzeug.security import generate_password_hash, check_password_hash

# --- Load Environment Variables ---
print(f"Current Working Directory: {os.getcwd()}")
print(f"Server script location: {os.path.dirname(__file__)}")
dotenv_path = os.path.join(os.path.dirname(__file__), '.env')
print(f"Expected .env path: {dotenv_path}")
found_dotenv = load_dotenv(dotenv_path=dotenv_path, verbose=True)
print(f".env file found by load_dotenv: {found_dotenv}")

app = Flask(__name__)

# --- Database Configuration ---
db_url = os.getenv("DATABASE_URL")
print(f"Value of DATABASE_URL after getenv: {db_url}")
if not db_url:
    raise RuntimeError("❌ DATABASE_URL is not set. Refusing to use fallback SQLite. Please check your .env file.")
else:
    print("Successfully loaded DATABASE_URL from environment.")
    if not db_url.startswith("postgresql"):
        print("WARN: DATABASE_URL does not appear to be a PostgreSQL URL. Features like regexp_replace might fail.")
    else:
        try:
            import psycopg2
        except ImportError:
            print("ERROR: psycopg2 (or psycopg2-binary) is required for PostgreSQL but not installed.")
            print("       Install it using: pip install psycopg2-binary")

app.config['SQLALCHEMY_DATABASE_URI'] = db_url
app.config['SQLALCHEMY_TRACK_MODIFICATIONS'] = False
app.config['SQLALCHEMY_ENGINE_OPTIONS'] = {'pool_size': 5, 'max_overflow': 10, 'pool_timeout': 30, 'pool_recycle': 1800}
db = SQLAlchemy(app)

# --- Database Models ---

# Existing model for cell data
class CellData(db.Model):
    __tablename__ = 'cell_data'
    id = db.Column(db.Integer, primary_key=True)
    user_id = db.Column(db.String(80), nullable=False, index=True)
    email = db.Column(db.String(120), nullable=True, index=True)  # <-- Add this line
    operator = db.Column(db.String(120), nullable=True)
    signal_power = db.Column(db.String(50), nullable=True)
    snr = db.Column(db.String(50), nullable=True)
    network_type = db.Column(db.String(20), nullable=True, index=True)
    frequency_band = db.Column(db.String(100), nullable=True)
    cell_id = db.Column(db.String(50), nullable=True)
    client_timestamp = db.Column(db.String(50), nullable=True)
    user_ip = db.Column(db.String(45), nullable=True)
    user_mac = db.Column(db.String(17), nullable=True, index=True)
    device_brand = db.Column(db.String(50), nullable=True)
    upload_time = db.Column(db.DateTime(timezone=True), server_default=func.now(), index=True)

    def __repr__(self):
        return f'<CellData ID:{self.id} Email:{self.email} User:{self.user_id} Brand:{self.device_brand}>'

    def to_dict(self):
        return {
            'id': self.id, 'user_id': self.user_id, 'email': self.email,
            'operator': self.operator, 'signal_power': self.signal_power, 'snr': self.snr,
            'network_type': self.network_type, 'frequency_band': self.frequency_band,
            'cell_id': self.cell_id, 'client_timestamp': self.client_timestamp,
            'user_ip': self.user_ip, 'user_mac': self.user_mac, 'device_brand': self.device_brand,
            'upload_time': self.upload_time.isoformat() if self.upload_time else None
        }


# --- New User model for registration ---
class User(db.Model):
    __tablename__ = 'users'
    id = db.Column(db.Integer, primary_key=True)
    name = db.Column(db.String(120), nullable=False)
    email = db.Column(db.String(120), nullable=False, unique=True)
    password_hash = db.Column(db.String(128), nullable=False)
    created_at = db.Column(db.DateTime(timezone=True), server_default=func.now())

    def __repr__(self):
        return f'<User ID:{self.id} Name:{self.name} Email:{self.email}>'

# --- Helper Function for Period-Based Stats ---
def calculate_stats_for_period(start_dt, end_dt):
    """Calculates statistics for data within a specific time window."""
    period_stats = {}
    is_postgresql = db.engine.dialect.name == 'postgresql'
    base_query = CellData.query.filter(
        CellData.upload_time >= start_dt, CellData.upload_time < end_dt
    )
    period_stats['active_user_count'] = base_query.distinct(CellData.user_id).count()

    # --- Latest Data ---
    latest_data_per_user = []
    if period_stats['active_user_count'] > 0:
        latest_times_subquery = base_query.with_entities(
            CellData.user_id, func.max(CellData.upload_time).label('latest_time')
        ).group_by(CellData.user_id).subquery()
        latest_data_query = db.session.query(CellData).join(
            latest_times_subquery,
            (CellData.user_id == latest_times_subquery.c.user_id) &
            (CellData.upload_time == latest_times_subquery.c.latest_time)
        ).order_by(desc(CellData.upload_time))
        latest_data_per_user = [data.to_dict() for data in latest_data_query.all()]
    period_stats['latest_data'] = latest_data_per_user

    # --- Distributions ---
    network_distribution = {}
    operator_distribution = {}
    device_brand_distribution = {}
    for data_dict in latest_data_per_user:
        net_type = data_dict.get('network_type') or 'Unknown'
        operator = data_dict.get('operator') or 'Unknown'
        brand = data_dict.get('device_brand') or 'Unknown'
        network_distribution[net_type] = network_distribution.get(net_type, 0) + 1
        operator_distribution[operator] = operator_distribution.get(operator, 0) + 1
        device_brand_distribution[brand] = device_brand_distribution.get(brand, 0) + 1
    period_stats['network_distribution'] = network_distribution
    period_stats['operator_distribution'] = operator_distribution
    period_stats['device_brand_distribution'] = device_brand_distribution

    # --- Averages & Connectivity % ---
    avg_signal_by_network = {}
    avg_snr_by_network = {}
    avg_signal_per_device = {}
    operator_connectivity = {}
    network_connectivity = {}
    total_records = base_query.count()

    if total_records > 0:
        operators_query = base_query.with_entities(func.coalesce(CellData.operator, 'Unknown').label('operator'),
                                                   func.count(CellData.id).label('count')).group_by('operator')
        operator_counts = {row.operator: row.count for row in operators_query.all()}
        [operator_connectivity.update({op: round((cnt / total_records) * 100, 1)})
         for op, cnt in operator_counts.items()]
        networks_query = base_query.with_entities(func.coalesce(CellData.network_type, 'Unknown').label('network'),
                                                  func.count(CellData.id).label('count')).group_by('network')
        network_counts = {row.network: row.count for row in networks_query.all()}
        [network_connectivity.update({net: round((cnt / total_records) * 100, 1)})
         for net, cnt in network_counts.items()]

        if is_postgresql:
            signal_net_query = base_query.filter(CellData.signal_power.isnot(None)).with_entities(
                CellData.network_type,
                func.avg(cast(func.regexp_replace(CellData.signal_power, r'[^-0-9.]', '', 'g'), Float)).label('avg_signal')
            ).group_by(CellData.network_type)
            [avg_signal_by_network.update({(row.network_type or 'Unknown'): float(row.avg_signal)})
             for row in signal_net_query if row.avg_signal is not None]

            # Query for avg SNR by network using raw SQL
            snr_net_query = db.session.execute(text("""
                SELECT network_type,
                       AVG(CAST(regexp_replace(snr, '[^0-9\\-.]', '', 'g') AS FLOAT)) AS avg_snr
                FROM cell_data
                WHERE snr IS NOT NULL
                  AND snr ~ '[-]?[0-9]+\\.?[0-9]*'
                  AND upload_time >= :start AND upload_time < :end
                GROUP BY network_type
            """), {'start': start_dt, 'end': end_dt}).fetchall()

            for row in snr_net_query:
                if row.avg_snr is not None:
                    avg_snr_by_network[row.network_type or 'Unknown'] = float(row.avg_snr)

            signal_dev_query = base_query.filter(CellData.signal_power.isnot(None)).with_entities(
                CellData.user_id,
                func.avg(cast(func.regexp_replace(CellData.signal_power, r'[^-0-9.]', '', 'g'), Float)).label('avg_signal')
            ).group_by(CellData.user_id)
            [avg_signal_per_device.update({row.user_id: float(row.avg_signal)})
             for row in signal_dev_query if row.avg_signal is not None]
        else:
            print(f"WARN: Skipping Signal/SNR average calculation for period {start_dt}-{end_dt} - requires PostgreSQL.")

    period_stats['avg_signal_by_network'] = avg_signal_by_network
    period_stats['avg_snr_by_network'] = avg_snr_by_network
    period_stats['avg_signal_per_device'] = avg_signal_per_device
    period_stats['operator_connectivity'] = operator_connectivity
    period_stats['network_connectivity'] = network_connectivity
    return period_stats

@app.route('/upload', methods=['POST'])
def receive_cell_data():
    data = request.get_json()
    if not data:
        return jsonify({'status': 'error', 'message': 'No JSON data received'}), 400
    print(f"📡 Received Raw Data: {data}")

    required_fields = ['email', 'clientTimestamp']
    missing = [field for field in required_fields if field not in data or not data.get(field)]
    if missing:
        error_msg = f'Missing or empty required fields: {", ".join(missing)}'
        print(f"Error: {error_msg}")
        return jsonify({'status': 'error', 'message': error_msg}), 400

    email = data.get('email')
    user = User.query.filter_by(email=email).first()
    if not user:
        return jsonify({'status': 'error', 'message': f"No user found with email: {email}"}), 404

    user_id = str(user.id)

    try:
        new_data = CellData(
            user_id=user_id,
            email=email,  # <-- Save the email in the DB
            operator=data.get('operator'),
            signal_power=data.get('signalPower'),
            snr=data.get('snr'),
            network_type=data.get('networkType'),
            frequency_band=data.get('frequencyBand'),
            cell_id=data.get('cellId'),
            client_timestamp=data.get('clientTimestamp'),
            user_ip=data.get('ipAddress'),
            user_mac=data.get('macAddress'),
            device_brand=data.get('deviceBrand')
        )
        db.session.add(new_data)
        db.session.commit()
        print(f"✅ Data stored successfully: ID={new_data.id}, Email={email}, Brand={new_data.device_brand}")
        return jsonify({'status': 'success', 'message': 'Data received and stored'}), 201

    except Exception as e:
        db.session.rollback()
        print(f"❌ Error storing data for {email}: {e}")
        traceback.print_exc()
        return jsonify({'status': 'error', 'message': 'Internal server error during data storage.'}), 500

# --- New Registration Endpoint ---
@app.route('/register', methods=['POST'])
def register_user():
    """
    Expects a JSON payload with:
      - name
      - email
      - password
    """
    data = request.get_json()
    if not data:
        return jsonify({'status': 'error', 'message': 'No JSON data received'}), 400

    name = data.get('name')
    email = data.get('email')
    password = data.get('password')

    if not name or not email or not password:
        return jsonify({'status': 'error', 'message': 'Missing required fields: name, email, and password'}), 400

    # Check if user with provided email already exists
    existing_user = User.query.filter_by(email=email).first()
    if existing_user:
        return jsonify({'status': 'error', 'message': 'User with this email already exists'}), 400

    try:
        # Hash the password before storing
        password_hash = generate_password_hash(password)
        new_user = User(name=name, email=email, password_hash=password_hash)
        db.session.add(new_user)
        db.session.commit()
        print(f"✅ User registered successfully: ID={new_user.id}, Name={new_user.name}, Email={new_user.email}")
        return jsonify({'status': 'success', 'message': 'Registration successful', 'user_id': new_user.id}), 201
    except Exception as e:
        db.session.rollback()
        print(f"❌ Error during registration: {e}")
        traceback.print_exc()
        return jsonify({'status': 'error', 'message': 'Internal server error during registration.'}), 500

@app.route("/login", methods=["POST"])
def login_user():
    try:
        data = request.get_json()
        email = data.get("email")
        password = data.get("password")

        if not email or not password:
            return jsonify({"success": False, "message": "Missing email or password"}), 400

        user = User.query.filter_by(email=email).first()
        if user and check_password_hash(user.password_hash, password):
            return jsonify({
                "success": True,
                "message": "Login successful",
                "name": user.name,
                "id": str(user.id)
            }), 200
        else:
            return jsonify({"success": False, "message": "Invalid credentials"}), 401

    except Exception as e:
        print(f"Login error: {e}")
        traceback.print_exc()
        return jsonify({"success": False, "message": "Internal server error"}), 500

# --- Other Endpoints (Web Dashboard and App Stats) ---

@app.route('/')
def index():
    return render_template('index.html')

@app.route('/api/stats')
def get_web_stats():
    """Provides statistics for the web dashboard based on relative time periods."""
    try:
        time_period = request.args.get('period', '1h')
        period_mapping = {
            '1m': timedelta(minutes=1), '5m': timedelta(minutes=5),
            '15m': timedelta(minutes=15), '30m': timedelta(minutes=30),
            '1h': timedelta(hours=1), '6h': timedelta(hours=6),
            '12h': timedelta(hours=12), '24h': timedelta(hours=24),
            '7d': timedelta(days=7), '30d': timedelta(days=30)
        }
        time_delta = period_mapping.get(time_period, timedelta(hours=1))
        end_dt = datetime.now(timezone.utc)
        start_dt = end_dt - time_delta
        period_stats = calculate_stats_for_period(start_dt, end_dt)
        total_unique_users = db.session.query(func.count(distinct(CellData.user_id))).scalar() or 0
        all_devices_query = db.session.query(
            CellData.user_mac,
            db.session.query(CellData.user_ip)
                .filter(CellData.user_mac == CellData.user_mac)
                .order_by(desc(CellData.upload_time))
                .limit(1)
                .scalar_subquery().label('last_ip'),
            func.max(CellData.upload_time).label('last_seen')
        ).filter(CellData.user_mac.isnot(None)).group_by(CellData.user_mac).order_by(desc('last_seen'))
        all_devices = [
            {'mac': row.user_mac, 'ip': row.last_ip, 'last_seen': row.last_seen.isoformat() if row.last_seen else None}
            for row in all_devices_query.all()
        ]
        full_stats = {
            **period_stats, 'total_unique_users': total_unique_users,
            'all_devices': all_devices,
            'stats_time_utc': datetime.now(timezone.utc).isoformat(),
            'data_window': time_period
        }
        return jsonify(full_stats), 200
    except Exception as e:
        print(f"❌ Error generating stats in /api/stats endpoint: {e}")
        traceback.print_exc()
        err_str = str(e).lower()
        if "no such function: regexp_replace" in err_str or "regular expression support is not available" in err_str:
            err_msg_for_client = "Error: Stats calculation requires features not available in the current database setup (e.g., SQLite)."
        else:
            err_msg_for_client = "An internal error occurred while generating statistics."
        return jsonify({'status': 'error', 'message': err_msg_for_client}), 500

@app.route('/api/user-stats', methods=['GET'])
def get_user_stats():
    """Provides optimized statistics for a specific user based on a date range (now uses email instead of user ID)."""
    email = request.args.get('email') 
    start_date_str = request.args.get('start_date')
    end_date_str = request.args.get('end_date')
    limit = request.args.get('limit', 1000, type=int)

    if not email:
        return jsonify({'status': 'error', 'message': "Missing 'email' query parameter."}), 400

    # 🔍 Find the user ID from the email
    user = User.query.filter_by(email=email).first()
    if not user:
        return jsonify({'status': 'error', 'message': f"No user found with email: {email}"}), 404

    user_id = str(user.id)

    offset = timedelta(hours=3)

    if not start_date_str or not end_date_str:
        end_dt = datetime.now(timezone.utc)
        start_dt = end_dt - timedelta(hours=1)
    else:
        try:
            start_dt = datetime.strptime(start_date_str, '%Y-%m-%d %H:%M:%S') - offset
            end_dt = datetime.strptime(end_date_str, '%Y-%m-%d %H:%M:%S') - offset
            start_dt = start_dt.replace(tzinfo=timezone.utc)
            end_dt = end_dt.replace(tzinfo=timezone.utc)
        except ValueError:
            return jsonify({'status': 'error', 'message': "Invalid date format. Use YYYY-MM-DD HH:MM:SS."}), 400

    try:
        is_postgresql = db.engine.dialect.name == 'postgresql'

        base_query = CellData.query.filter(
            CellData.user_id == user_id,
            CellData.upload_time >= start_dt,
            CellData.upload_time <= end_dt
        )

        total_data_points = base_query.count()

        if total_data_points == 0:
            return jsonify({
                'status': 'success',
                'message': 'No data found for this user in the specified time range',
                'data': {
                    'signalData': [],
                    'networkData': [],
                    'summary': {}
                }
            }), 200

        downsample_factor = max(1, (total_data_points // limit)) if limit > 0 else 1

        network_stats = {}
        network_counts_query = base_query.with_entities(
            CellData.network_type,
            func.count(CellData.id).label('count')
        ).group_by(CellData.network_type).all()

        for row in network_counts_query:
            network_type = row.network_type or "UNKNOWN"
            network_stats[network_type] = row.count

        total_count = sum(network_stats.values())
        network_distribution = {}
        if total_count > 0:
            raw_distribution = {
                k: max(0, v / total_count * 100)
                for k, v in network_stats.items()
            }

            total_percentage = sum(raw_distribution.values())
            if total_percentage > 0:
                network_distribution = {
                    k: round((v / total_percentage) * 100, 1)
                    for k, v in raw_distribution.items()
                }

            network_distribution = {
                k: v for k, v in network_distribution.items() if v >= 0.5
            }

            if network_distribution and sum(network_distribution.values()) != 100:
                total = sum(network_distribution.values())
                network_distribution = {
                    k: round((v / total) * 100, 1)
                    for k, v in network_distribution.items()
                }

        if downsample_factor > 1:
            if is_postgresql:
                sampled_query = db.session.execute(text(f"""
                    WITH numbered_rows AS (
                        SELECT 
                            upload_time, 
                            signal_power,
                            snr, 
                            network_type,
                            ROW_NUMBER() OVER (ORDER BY upload_time) as row_num
                        FROM cell_data
                        WHERE user_id = :user_id 
                          AND upload_time BETWEEN :start_dt AND :end_dt
                    )
                    SELECT 
                        upload_time, 
                        signal_power,
                        snr,
                        network_type
                    FROM numbered_rows
                    WHERE row_num % :downsample = 0
                    ORDER BY upload_time
                """), {
                    'user_id': user_id,
                    'start_dt': start_dt,
                    'end_dt': end_dt,
                    'downsample': downsample_factor
                }).fetchall()
            else:
                all_data = base_query.order_by(CellData.upload_time).all()
                sampled_query = all_data[::downsample_factor]
        else:
            sampled_query = base_query.order_by(CellData.upload_time).all()

        signal_data = []
        network_data = []
        snr_values = []

        network_type_map = {
            "LTE": 4, "5G": 5, "3G": 3, "2G": 2, "WIFI": 6, "UNKNOWN": 0
        }

        for row in sampled_query:
            timestamp = int(row.upload_time.timestamp() * 1000) if hasattr(row, 'upload_time') else int(row[0].timestamp() * 1000)
            signal_power = row.signal_power if hasattr(row, 'signal_power') else row[1]
            snr = row.snr if hasattr(row, 'snr') else row[2]
            network_type = row.network_type if hasattr(row, 'network_type') else row[3]

            if signal_power:
                try:
                    import re
                    match = re.search(r'-?\d+\.?\d*', signal_power)
                    if match:
                        signal_value = float(match.group(0))
                        signal_data.append({
                            'timestamp': timestamp,
                            'signalStrength': signal_value
                        })
                except (ValueError, TypeError):
                    pass

            net_type = network_type or "UNKNOWN"
            net_val = network_type_map.get(net_type, 0)
            network_data.append({
                'timestamp': timestamp,
                'networkType': net_type,
                'networkTypeValue': net_val
            })

            if snr:
                try:
                    import re
                    match = re.search(r'-?\d+\.?\d*', snr)
                    if match:
                        snr_values.append(float(match.group(0)))
                except (ValueError, TypeError):
                    pass

        summary = {
            'dataPoints': total_data_points,
            'networkDistribution': network_distribution,
            'sampledPoints': len(signal_data),
            'downsampleFactor': downsample_factor
        }

        if is_postgresql:
            avg_signal_query = db.session.execute(text("""
                SELECT AVG(CAST(regexp_replace(signal_power, '[^-0-9.]', '', 'g') AS FLOAT)) as avg_signal
                FROM cell_data
                WHERE user_id = :user_id 
                  AND upload_time BETWEEN :start_dt AND :end_dt
                  AND signal_power ~ '[-]?[0-9]+\\.?[0-9]*'
            """), {'user_id': user_id, 'start_dt': start_dt, 'end_dt': end_dt}).fetchone()

            avg_snr_query = db.session.execute(text("""
                SELECT AVG(CAST(regexp_replace(snr, '[^-0-9.]', '', 'g') AS FLOAT)) as avg_snr
                FROM cell_data
                WHERE user_id = :user_id 
                  AND upload_time BETWEEN :start_dt AND :end_dt
                  AND snr ~ '[-]?[0-9]+\\.?[0-9]*'
            """), {'user_id': user_id, 'start_dt': start_dt, 'end_dt': end_dt}).fetchone()

            summary['avgSignalStrength'] = float(avg_signal_query.avg_signal) if avg_signal_query.avg_signal else None
            summary['avgSnr'] = float(avg_snr_query.avg_snr) if avg_snr_query.avg_snr else None
        else:
            summary['avgSignalStrength'] = sum([s['signalStrength'] for s in signal_data]) / len(signal_data) if signal_data else None
            summary['avgSnr'] = sum(snr_values) / len(snr_values) if snr_values else None

        return jsonify({
            'status': 'success',
            'data': {
                'signalData': signal_data,
                'networkData': network_data,
                'summary': summary,
                'timeRange': {
                    'start': start_dt.isoformat(),
                    'end': end_dt.isoformat()
                }
            }
        }), 200

    except Exception as e:
        print(f"❌ Error generating user stats: {e}")
        traceback.print_exc()
        return jsonify({'status': 'error', 'message': f"An error occurred while fetching statistics: {str(e)}"}), 500

@app.route('/api/server-user-stats', methods=['GET'])
def get_user_stats_for_dashboard():
    """Provides user info and connection history for the User Stats dashboard tab."""
    email = request.args.get('email')
    period = request.args.get('period', '1h')
    
    if not email:
        return jsonify({'status': 'error', 'message': "Missing 'email' query parameter."}), 400
    
    # Find the user by email
    user = User.query.filter_by(email=email).first()
    if not user:
        return jsonify({'status': 'error', 'message': f"No user found with email: {email}"}), 404
    
    user_id = str(user.id)
    
    # Map period to time delta
    period_mapping = {
        '1m': timedelta(minutes=1), '5m': timedelta(minutes=5),
        '15m': timedelta(minutes=15), '30m': timedelta(minutes=30),
        '1h': timedelta(hours=1), '6h': timedelta(hours=6),
        '12h': timedelta(hours=12), '24h': timedelta(hours=24),
        '7d': timedelta(days=7), '30d': timedelta(days=30)
    }
    time_delta = period_mapping.get(period, timedelta(hours=1))
    end_dt = datetime.now(timezone.utc)
    start_dt = end_dt - time_delta
    
    try:
        # Get the user's most recent connection data
        latest_data = CellData.query.filter(
            CellData.user_id == user_id
        ).order_by(desc(CellData.upload_time)).first()
        
        # Get connection history within the time period
        connection_history = CellData.query.filter(
            CellData.user_id == user_id,
            CellData.upload_time >= start_dt,
            CellData.upload_time <= end_dt
        ).order_by(desc(CellData.upload_time)).limit(100).all()
        
        # Format user info for response
        user_info = {
            'email': email,
            'user_id': user_id,
            'name': user.name,
            'mac': latest_data.user_mac if latest_data else None,
            'ip': latest_data.user_ip if latest_data else None,
            'device': latest_data.device_brand if latest_data else None,
            'last_seen': latest_data.upload_time.isoformat() if latest_data and latest_data.upload_time else None
        }
        
        # Format connection history for response
        history_list = []
        for record in connection_history:
            # Convert signal power and SNR to numeric values if possible
            signal_power = None
            snr = None
            
            if record.signal_power:
                try:
                    import re
                    match = re.search(r'-?\d+\.?\d*', record.signal_power)
                    if match:
                        signal_power = float(match.group(0))
                except (ValueError, TypeError):
                    pass
            
            if record.snr:
                try:
                    import re
                    match = re.search(r'-?\d+\.?\d*', record.snr)
                    if match:
                        snr = float(match.group(0))
                except (ValueError, TypeError):
                    pass
            
            history_list.append({
                'client_timestamp': record.client_timestamp,
                'operator': record.operator,
                'network_type': record.network_type,
                'signal_power': signal_power,
                'snr': snr,
                'frequency_band': record.frequency_band,
                'cell_id': record.cell_id,
                'upload_time': record.upload_time.isoformat() if record.upload_time else None
            })
        
        # Get the latest connection details
        latest_connection = history_list[0] if history_list else None
        
        return jsonify({
            'userInfo': user_info,
            'connectionHistory': history_list,
            'latestConnection': latest_connection
        }), 200
        
    except Exception as e:
        print(f"❌ Error generating user stats for dashboard: {e}")
        traceback.print_exc()
        return jsonify({'status': 'error', 'message': f"An error occurred while fetching user statistics: {str(e)}"}), 500

# --- Initialization / Helper ---
def create_tables():
    """Creates database tables if they don't exist. Use with caution."""
    with app.app_context():
        print("Attempting to create database tables (if they don't exist)...")
        try:
            db.create_all()
            print("✅ Tables checked/created successfully.")
            print("\n" + "=" * 60)
            print("REMINDER 1: If the 'cell_data' table existed BEFORE schema changes")
            print("            (like renaming sinr->snr or adding device_brand),")
            print("            `db.create_all()` DID NOT automatically apply them.")
            print("            Schema changes require manual SQL or a migration tool.")
            print("\nREMINDER 2: Ensure 'snr' & 'device_brand' columns exist in your DB!")
            print("=" * 60 + "\n")
        except Exception as e:
            print(f"❌ Error creating/checking tables: {e}")
            traceback.print_exc()

# --- Main Execution ---
if __name__ == '__main__':
    # create_tables()  # Uncomment if you want to create tables automatically
    print(f"Database URL configured in Flask app: {app.config.get('SQLALCHEMY_DATABASE_URI')}")
    print(f"Starting Flask server - Listening on http://0.0.0.0:5000")
    print("Web Dashboard: http://127.0.0.1:5000/")
    print("Web API: /api/stats?period=<1m|5m|...>")
    print("App API: /api/app-stats?start_date=YYYY-MM-DD&end_date=YYYY-MM-DD")
    print("Upload: POST /upload")
    print("Registration: POST /register")
    print("(Press CTRL+C to quit)")

    url_to_open = "http://127.0.0.1:5000/"

    def open_browser():
        print(f"Attempting to automatically open browser at: {url_to_open}")

    try:
        webbrowser.open(url_to_open)
        print("Browser open command issued.")
    except Exception as browser_e:
        print(f"Could not automatically open browser: {browser_e}")
    if os.environ.get("WERKZEUG_RUN_MAIN") != "true":
        browser_timer = threading.Timer(1.5, open_browser)
        browser_timer.start()
    app.run(host='0.0.0.0', port=5000, debug=True, use_reloader=True)
    