# --- START OF FILE server.py ---

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

# import re # Removed unused import re

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
    print("WARN: DATABASE_URL environment variable not set OR .env not loaded correctly.")
    db_folder = os.path.abspath(os.path.dirname(__file__))
    if not os.path.exists(db_folder): os.makedirs(db_folder)
    db_path = os.path.join(db_folder, 'cell_data.db')
    db_url = f"sqlite:///{db_path}"
    print(f"Using fallback SQLite database at: {db_path}")
    print("       For PostgreSQL, check .env file location, name, content, and permissions.")
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


# --- Database Model ---
class CellData(db.Model):
    __tablename__ = 'cell_data'
    id = db.Column(db.Integer, primary_key=True)
    user_id = db.Column(db.String(80), nullable=False, index=True)
    operator = db.Column(db.String(120), nullable=True)
    signal_power = db.Column(db.String(50), nullable=True)
    # *** RENAMED sinr to snr ***
    snr = db.Column(db.String(50), nullable=True)  # Stores raw string like "10 dB" or "15 dB (SS-SINR)"
    network_type = db.Column(db.String(20), nullable=True, index=True)
    frequency_band = db.Column(db.String(100), nullable=True)
    cell_id = db.Column(db.String(50), nullable=True)
    client_timestamp = db.Column(db.String(50), nullable=True)
    user_ip = db.Column(db.String(45), nullable=True)
    user_mac = db.Column(db.String(17), nullable=True, index=True)
    device_brand = db.Column(db.String(50), nullable=True)
    upload_time = db.Column(db.DateTime(timezone=True), server_default=func.now(), index=True)

    def __repr__(self):
        # Updated repr
        return f'<CellData ID:{self.id} User:{self.user_id} Brand:{self.device_brand} SNR:{self.snr} Upload:{self.upload_time}>'

    def to_dict(self):
        # Updated dict key
        return {
            'id': self.id, 'user_id': self.user_id, 'operator': self.operator,
            'signal_power': self.signal_power, 'snr': self.snr, 'network_type': self.network_type,  # Renamed key
            'frequency_band': self.frequency_band, 'cell_id': self.cell_id,
            'client_timestamp': self.client_timestamp, 'user_ip': self.user_ip,
            'user_mac': self.user_mac, 'device_brand': self.device_brand,
            'upload_time': self.upload_time.isoformat() if self.upload_time else None
        }


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
            (CellData.user_id == latest_times_subquery.c.user_id) & (
                        CellData.upload_time == latest_times_subquery.c.latest_time)
        ).order_by(desc(CellData.upload_time))
        latest_data_per_user = [data.to_dict() for data in latest_data_query.all()]
    period_stats['latest_data'] = latest_data_per_user

    # --- Distributions ---
    network_distribution = {};
    operator_distribution = {};
    device_brand_distribution = {}
    for data_dict in latest_data_per_user:
        net_type = data_dict.get('network_type') or 'Unknown';
        operator = data_dict.get('operator') or 'Unknown';
        brand = data_dict.get('device_brand') or 'Unknown'
        network_distribution[net_type] = network_distribution.get(net_type, 0) + 1;
        operator_distribution[operator] = operator_distribution.get(operator, 0) + 1;
        device_brand_distribution[brand] = device_brand_distribution.get(brand, 0) + 1
    period_stats['network_distribution'] = network_distribution;
    period_stats['operator_distribution'] = operator_distribution;
    period_stats['device_brand_distribution'] = device_brand_distribution

    # --- Averages & Connectivity % ---
    avg_signal_by_network = {};
    avg_snr_by_network = {};
    avg_signal_per_device = {};
    operator_connectivity = {};
    network_connectivity = {}
    total_records = base_query.count()

    if total_records > 0:
        operators_query = base_query.with_entities(func.coalesce(CellData.operator, 'Unknown').label('operator'),
                                                   func.count(CellData.id).label('count')).group_by('operator')
        operator_counts = {row.operator: row.count for row in operators_query.all()};
        [operator_connectivity.update({op: round((cnt / total_records) * 100, 1)}) for op, cnt in
         operator_counts.items()]
        networks_query = base_query.with_entities(func.coalesce(CellData.network_type, 'Unknown').label('network'),
                                                  func.count(CellData.id).label('count')).group_by('network')
        network_counts = {row.network: row.count for row in networks_query.all()};
        [network_connectivity.update({net: round((cnt / total_records) * 100, 1)}) for net, cnt in
         network_counts.items()]

        if is_postgresql:
            signal_net_query = base_query.filter(CellData.signal_power.isnot(None)).with_entities(CellData.network_type,
                                                                                                  func.avg(cast(
                                                                                                      func.regexp_replace(
                                                                                                          CellData.signal_power,
                                                                                                          r'[^-0-9.]',
                                                                                                          '', 'g'),
                                                                                                      Float)).label(
                                                                                                      'avg_signal')).group_by(
                CellData.network_type)
            [avg_signal_by_network.update({(row.network_type or 'Unknown'): float(row.avg_signal)}) for row in
             signal_net_query if row.avg_signal is not None]

            # *** UPDATED to query CellData.snr and store in avg_snr_by_network ***
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

            signal_dev_query = base_query.filter(CellData.signal_power.isnot(None)).with_entities(CellData.user_id,
                                                                                                  func.avg(cast(
                                                                                                      func.regexp_replace(
                                                                                                          CellData.signal_power,
                                                                                                          r'[^-0-9.]',
                                                                                                          '', 'g'),
                                                                                                      Float)).label(
                                                                                                      'avg_signal')).group_by(
                CellData.user_id)
            [avg_signal_per_device.update({row.user_id: float(row.avg_signal)}) for row in signal_dev_query if
             row.avg_signal is not None]
        else:
            print(
                f"WARN: Skipping Signal/SNR average calculation for period {start_dt}-{end_dt} - requires PostgreSQL.")

    period_stats['avg_signal_by_network'] = avg_signal_by_network
    # *** UPDATED dictionary key ***
    period_stats['avg_snr_by_network'] = avg_snr_by_network
    period_stats['avg_signal_per_device'] = avg_signal_per_device
    period_stats['operator_connectivity'] = operator_connectivity
    period_stats['network_connectivity'] = network_connectivity
    return period_stats


# --- Routes ---
@app.route('/upload', methods=['POST'])
def receive_cell_data():
    data = request.get_json()
    if not data: return jsonify({'status': 'error', 'message': 'No JSON data received'}), 400
    print(f"📡 Received Raw Data: {data}")
    required_fields = ['userId', 'clientTimestamp']
    missing = [field for field in required_fields if field not in data or not data.get(field)];
    if missing:
        error_msg = f'Missing or empty required fields: {", ".join(missing)}';
        print(f"Error: {error_msg}");
        print(f"Problematic Data: {data}");
        return jsonify({'status': 'error', 'message': error_msg}), 400

    user_id = data.get('userId');
    client_timestamp = data.get('clientTimestamp');
    user_ip = data.get('ipAddress', None);
    user_mac = data.get('macAddress', None)
    operator = data.get('operator', None);
    signal_power = data.get('signalPower', None)
    # *** UPDATED to get 'snr' key ***
    snr_value = data.get('snr', None)
    network_type = data.get('networkType', None);
    frequency_band = data.get('frequencyBand', None);
    cell_id = data.get('cellId', None);
    device_brand = data.get('deviceBrand', None)
    try:
        new_data = CellData(
            user_id=user_id, operator=operator, signal_power=signal_power,
            # *** UPDATED to save to snr field ***
            snr=snr_value,
            network_type=network_type, frequency_band=frequency_band, cell_id=cell_id,
            client_timestamp=client_timestamp, user_ip=user_ip, user_mac=user_mac,
            device_brand=device_brand
        )
        db.session.add(new_data);
        db.session.commit()
        print(
            f"✅ Data stored successfully: ID={new_data.id}, User={new_data.user_id}, Brand={new_data.device_brand}, IP={new_data.user_ip}, MAC={new_data.user_mac}")
        return jsonify(
            {'status': 'success', 'message': 'Data received and stored', 'db_id': new_data.id, 'received_ip': user_ip,
             'received_mac': user_mac}), 201
    except Exception as e:
        db.session.rollback();
        print(f"❌ Error storing data for user {user_id}: {e}");
        traceback.print_exc()
        if "column cell_data.device_brand does not exist" in str(e).lower(): print(
            "\n HINT: The 'device_brand' column seems missing. Update your DB schema!\n")
        # *** Add check for missing snr column ***
        if "column cell_data.snr does not exist" in str(e).lower(): print(
            "\n HINT: The 'snr' column seems missing. Update your DB schema (Rename from sinr or add new)!\n")
        return jsonify({'status': 'error', 'message': 'Internal server error during data storage.'}), 500


@app.route('/')
def index(): return render_template('index.html')


# --- API Endpoint for Web Dashboard ---
@app.route('/api/stats')
def get_web_stats():
    """Provides statistics for the web dashboard based on relative time periods."""
    try:
        time_period = request.args.get('period', '1h');
        period_mapping = {'1m': timedelta(minutes=1), '5m': timedelta(minutes=5), '15m': timedelta(minutes=15),
                          '30m': timedelta(minutes=30), '1h': timedelta(hours=1), '6h': timedelta(hours=6),
                          '12h': timedelta(hours=12), '24h': timedelta(hours=24), '7d': timedelta(days=7),
                          '30d': timedelta(days=30)}
        time_delta = period_mapping.get(time_period, timedelta(hours=1));
        end_dt = datetime.now(timezone.utc);
        start_dt = end_dt - time_delta
        period_stats = calculate_stats_for_period(start_dt, end_dt)
        total_unique_users = db.session.query(func.count(distinct(CellData.user_id))).scalar() or 0
        all_devices_query = db.session.query(CellData.user_mac, db.session.query(CellData.user_ip).filter(
            CellData.user_mac == CellData.user_mac).order_by(desc(CellData.upload_time)).limit(
            1).scalar_subquery().label('last_ip'), func.max(CellData.upload_time).label('last_seen')).filter(
            CellData.user_mac.isnot(None)).group_by(CellData.user_mac).order_by(desc('last_seen'))
        all_devices = [
            {'mac': row.user_mac, 'ip': row.last_ip, 'last_seen': row.last_seen.isoformat() if row.last_seen else None}
            for row in all_devices_query.all()]
        full_stats = {**period_stats, 'total_unique_users': total_unique_users, 'all_devices': all_devices,
                      'stats_time_utc': datetime.now(timezone.utc).isoformat(), 'data_window': time_period}
        return jsonify(full_stats), 200
    except Exception as e:
        print(f"❌ Error generating stats in /api/stats endpoint: {e}");
        traceback.print_exc();
        err_str = str(e).lower()
        if "no such function: regexp_replace" in err_str or "regular expression support is not available" in err_str:
            err_msg_for_client = "Error: Stats calculation requires features not available in the current database setup (e.g., SQLite)."
        else:
            err_msg_for_client = "An internal error occurred while generating statistics."
        return jsonify({'status': 'error', 'message': err_msg_for_client}), 500


# --- NEW API Endpoint for Android App ---
@app.route('/api/app-stats')
def get_app_stats():
    """Provides statistics for the Android app based on a specific date range."""
    start_date_str = request.args.get('start_date');
    end_date_str = request.args.get('end_date')
    if not start_date_str or not end_date_str: return jsonify({'status': 'error',
                                                               'message': "Missing 'start_date' or 'end_date' query parameters (YYYY-MM-DD format required)."}), 400
    try:
        start_dt = datetime.strptime(start_date_str, '%Y-%m-%d').replace(tzinfo=timezone.utc)
        end_dt = (datetime.strptime(end_date_str, '%Y-%m-%d') + timedelta(days=1)).replace(tzinfo=timezone.utc)
        period_stats = calculate_stats_for_period(start_dt, end_dt)
        period_stats['requested_start_date'] = start_date_str;
        period_stats['requested_end_date'] = end_date_str;
        period_stats['stats_time_utc'] = datetime.now(timezone.utc).isoformat()
        period_stats.pop('latest_data', None);
        period_stats.pop('device_brand_distribution', None)
        return jsonify(period_stats), 200
    except ValueError:
        return jsonify({'status': 'error', 'message': "Invalid date format. Use YYYY-MM-DD."}), 400
    except Exception as e:
        print(f"❌ Error generating stats in /api/app-stats endpoint: {e}");
        traceback.print_exc();
        err_str = str(e).lower()
        if "no such function: regexp_replace" in err_str or "regular expression support is not available" in err_str:
            err_msg_for_client = "Error: Stats calculation requires features not available in the current database setup (e.g., SQLite)."
        else:
            err_msg_for_client = "An internal error occurred while generating statistics."
        return jsonify({'status': 'error', 'message': err_msg_for_client}), 500


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
            print(f"❌ Error creating/checking tables: {e}"); traceback.print_exc()


# --- Main Execution ---
if __name__ == '__main__':
    # create_tables() # Keep commented out

    print(f"Database URL configured in Flask app: {app.config.get('SQLALCHEMY_DATABASE_URI')}")
    print(f"Starting Flask server - Listening on http://0.0.0.0:5000")
    print("Web Dashboard: http://127.0.0.1:5000/")
    print("Web API: /api/stats?period=<1m|5m|...>")
    print("App API: /api/app-stats?start_date=YYYY-MM-DD&end_date=YYYY-MM-DD")
    print("Upload: POST /upload")
    print("(Press CTRL+C to quit)")

    url_to_open = "http://127.0.0.1:5000/"


    def open_browser():
        print(f"Attempting to automatically open browser at: {url_to_open}");


    try:
        webbrowser.open(url_to_open); print("Browser open command issued.")
    except Exception as browser_e:
        print(f"Could not automatically open browser: {browser_e}")
    if os.environ.get("WERKZEUG_RUN_MAIN") != "true": browser_timer = threading.Timer(1.5,
                                                                                      open_browser); browser_timer.start()
    app.run(host='0.0.0.0', port=5000, debug=True, use_reloader=True)

# --- END OF FILE server.py ---
