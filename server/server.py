import os
from datetime import datetime, timedelta, timezone
from flask import Flask, request, jsonify, render_template
from flask_sqlalchemy import SQLAlchemy
from sqlalchemy import desc, func, distinct
from dotenv import load_dotenv

load_dotenv()  # Load environment variables from .env file

app = Flask(__name__)

# --- Database Configuration ---
# Ensure your actual DB URL is securely stored in .env or environment variables
db_url = "postgresql://postgres.sqregetluaqenetlfioo:KikoBoudi123$@aws-0-eu-central-1.pooler.supabase.com:6543/postgres" # Prioritize environment variable

# Fallback for local development if DATABASE_URL is not set
if not db_url:
    print("WARN: DATABASE_URL not set in environment. Using default SQLite.")
    db_path = os.path.join(os.path.abspath(os.path.dirname(__file__)), 'cell_data.db')
    db_url = f"sqlite:///{db_path}"
    print(f"Using SQLite DB at: {db_path}")


app.config['SQLALCHEMY_DATABASE_URI'] = db_url
app.config['SQLALCHEMY_TRACK_MODIFICATIONS'] = False
db = SQLAlchemy(app)


# --- Database Model ---
class CellData(db.Model):
    __tablename__ = 'cell_data' # Explicit table name
    id = db.Column(db.Integer, primary_key=True)
    user_id = db.Column(db.String(80), nullable=False, index=True) # User identifier from app
    operator = db.Column(db.String(120), nullable=True)
    signal_power = db.Column(db.String(50), nullable=True)
    sinr = db.Column(db.String(50), nullable=True)
    network_type = db.Column(db.String(20), nullable=True, index=True)
    frequency_band = db.Column(db.String(100), nullable=True)
    cell_id = db.Column(db.String(50), nullable=True)
    client_timestamp = db.Column(db.String(50), nullable=True) # Timestamp from client

    # ****** ADDED IP and MAC Address columns ******
    user_ip = db.Column(db.String(45), nullable=True)  # Store IP Address (IPv6 compatible length)
    user_mac = db.Column(db.String(17), nullable=True) # Store MAC Address (XX:XX:XX:XX:XX:XX format)
    # ****** END ADDED ******

    # Server timestamp for accurate activity tracking
    upload_time = db.Column(db.DateTime(timezone=True), server_default=func.now(), index=True)

    def __repr__(self):
        # ****** UPDATED repr to include user_id, IP, MAC ******
        return f'<CellData ID:{self.id} User:{self.user_id} IP:{self.user_ip} MAC:{self.user_mac} Upload:{self.upload_time}>'

    def to_dict(self):
        """Helper to convert model instance to dictionary for JSON"""
        return {
            'id': self.id,
            'user_id': self.user_id,
            'operator': self.operator,
            'signal_power': self.signal_power,
            'sinr': self.sinr,
            'network_type': self.network_type,
            'frequency_band': self.frequency_band,
            'cell_id': self.cell_id,
            'client_timestamp': self.client_timestamp,
            # ****** ADDED IP and MAC to dictionary ******
            'user_ip': self.user_ip,
            'user_mac': self.user_mac,
            # ****** END ADDED ******
            'upload_time': self.upload_time.isoformat() if self.upload_time else None # Use ISO format
        }

# --- Routes ---
@app.route('/upload', methods=['POST'])
def receive_cell_data():
    """Receives cell data (including IP/MAC) from Android app and stores it."""
    data = request.get_json()

    if not data:
        return jsonify({'status': 'error', 'message': 'No JSON data received'}), 400

    # Log the raw data received for debugging
    print(f"📡 Received Raw Data: {data}")

    # --- Validation ---
    # Define required fields (adjust if IP/MAC are optional from the app)
    required_fields = ['userId', 'operator', 'networkType', 'clientTimestamp']
    # IP and MAC are not strictly required here, will use defaults if missing
    # required_fields.extend(['ipAddress', 'macAddress']) # Uncomment if they MUST be sent

    missing = [field for field in required_fields if field not in data or not data.get(field)]
    if missing:
         print(f"Error: Missing or empty required fields - {missing}")
         return jsonify({'status': 'error', 'message': f'Missing/empty required fields: {", ".join(missing)}'}), 400

    # --- Data Extraction ---
    user_id = data.get('userId') # Already validated it exists

    # ****** Extract IP and MAC using .get() with a default ******
    # Match the keys ('ipAddress', 'macAddress') expected from the Android app (like in the old version)
    user_ip = data.get('ipAddress', None) # Use None or 'N/A' as default if not provided
    user_mac = data.get('macAddress', None) # Use None or 'N/A' as default if not provided
    # ****** END EXTRACTION ******

    # --- Database Storage ---
    try:
        new_data = CellData(
            user_id=user_id,
            operator=data.get('operator'),
            signal_power=data.get('signalPower', 'N/A'), # Keep using 'N/A' for consistency if you prefer
            sinr=data.get('sinr', 'N/A'),
            network_type=data.get('networkType'),
            frequency_band=data.get('frequencyBand', 'N/A'),
            cell_id=data.get('cellId', 'N/A'),
            client_timestamp=data.get('clientTimestamp'),
            # ****** Assign extracted IP and MAC to the model ******
            user_ip=user_ip,
            user_mac=user_mac
            # ****** END ASSIGNMENT ******
            # upload_time is set automatically by the database
        )
        db.session.add(new_data)
        db.session.commit()

        # Log success including the new data
        print(f"💾 Data stored successfully for user {new_data.user_id} (DB ID: {new_data.id}, IP: {new_data.user_ip}, MAC: {new_data.user_mac})")

        # ****** Modify success response to include IP and MAC ******
        return jsonify({
            'status': 'success',
            'message': 'Data received and stored',
            'db_id': new_data.id,
            'user_ip': user_ip,   # Return the received IP
            'user_mac': user_mac  # Return the received MAC
        }), 201 # 201 Created status code is appropriate

    except Exception as e:
        db.session.rollback() # Rollback transaction on error
        print(f"Error storing data for user {user_id}: {e}")
        # Log the full traceback for detailed debugging on the server
        import traceback
        traceback.print_exc()
        # Provide a generic error message to the client for security
        return jsonify({'status': 'error', 'message': 'Internal server error during data storage.'}), 500


@app.route('/')
def index():
    """Serves the main dashboard HTML page."""
    # Ensure you have a 'templates' folder with 'index.html' in it
    return render_template('index.html')


@app.route('/api/stats')
def get_stats():
    """Provides statistics based on recent data (implicitly includes IP/MAC via to_dict)."""
    try:
        # Define the time window (last 1 minute)
        one_minute_ago = datetime.now(timezone.utc) - timedelta(minutes=1)

        # --- Calculate Stats ---
        # These queries benefit from the indexed user_id and upload_time

        # 1. Count distinct active users
        active_user_count = db.session.query(func.count(distinct(CellData.user_id)))\
                                      .filter(CellData.upload_time >= one_minute_ago)\
                                      .scalar() or 0

        # 2. Get latest data point for each active user
        # Subquery to find the max upload_time per user_id within the window
        latest_times_subquery = db.session.query(
            CellData.user_id,
            func.max(CellData.upload_time).label('latest_time')
        ).filter(CellData.upload_time >= one_minute_ago)\
         .group_by(CellData.user_id)\
         .subquery()

        # Join CellData with the subquery to get the full row for the latest time
        latest_data_query = db.session.query(CellData).join(
            latest_times_subquery,
            (CellData.user_id == latest_times_subquery.c.user_id) &
            (CellData.upload_time == latest_times_subquery.c.latest_time)
        ).order_by(desc(CellData.upload_time))

        # Convert results to dictionaries (now includes IP/MAC via the updated to_dict)
        latest_data_per_user = [data.to_dict() for data in latest_data_query.all()]

        # 3. Network Type Distribution among active users
        # Find unique active user IDs first
        active_user_ids_query = db.session.query(distinct(CellData.user_id))\
                                       .filter(CellData.upload_time >= one_minute_ago)
        active_user_ids = [item[0] for item in active_user_ids_query.all()]

        network_distribution = {}
        if active_user_ids:
            # Subquery to find the latest record time for each *active* user (across all their history)
            # This ensures we get the *current* network type for users active recently.
            latest_network_subquery = db.session.query(
                CellData.user_id,
                func.max(CellData.upload_time).label('latest_time')
            ).filter(CellData.user_id.in_(active_user_ids))\
             .group_by(CellData.user_id)\
             .subquery()

            # Query to get the network type for those latest records and count them
            latest_networks_query = db.session.query(
                CellData.network_type,
                func.count(CellData.network_type).label('count')
             ).join(
                 latest_network_subquery,
                (CellData.user_id == latest_network_subquery.c.user_id) &
                (CellData.upload_time == latest_network_subquery.c.latest_time)
             ).group_by(CellData.network_type) # Group by network type to get counts

            network_distribution = {row.network_type if row.network_type else "Unknown": row.count for row in latest_networks_query.all()}


        # --- Compile results ---
        stats = {
            'active_user_count': active_user_count,
            'latest_data': latest_data_per_user, # This now contains user_id, IP, MAC
            'network_distribution': network_distribution,
            'stats_time_utc': datetime.now(timezone.utc).isoformat(),
            'data_window_minutes': 1
        }

        return jsonify(stats), 200

    except Exception as e:
        print(f"Error generating stats: {e}")
        import traceback
        traceback.print_exc()
        return jsonify({'status': 'error', 'message': 'Error generating statistics.'}), 500


# --- Initialization ---
def create_tables():
    """Creates database tables if they don't exist."""
    # Use app.app_context() to ensure SQLAlchemy has the app's context
    with app.app_context():
        print("Attempting to create database tables...")
        try:
            db.create_all()
            print("Tables checked/created successfully.")
            # ****** IMPORTANT SCHEMA CHANGE NOTE ******
            print("\n" + "="*60)
            print("IMPORTANT: If the 'cell_data' table existed BEFORE adding")
            print("           'user_ip' and 'user_mac' columns, `db.create_all()`")
            print("           WILL NOT automatically add these new columns.")
            print("           You MUST update the table schema manually.")
            print("Options:")
            print("  1. Drop the existing 'cell_data' table (DATA WILL BE LOST)")
            print("     (e.g., using psql: `DROP TABLE cell_data;` then restart app)")
            print("  2. Use a database migration tool like Flask-Migrate (Alembic)")
            print("     to apply the schema changes without losing data.")
            print("="*60 + "\n")
            # ****** END NOTE ******
        except Exception as e:
            print(f"Error creating/checking tables: {e}")
            print("Please ensure the database server is running, accessible,")
            print("and the DATABASE_URL in your environment is correct.")


if __name__ == '__main__':
    # create_tables() # Check/create tables on startup
    print(f"Starting Flask server on host 0.0.0.0, port 5000")
    # use_reloader=True is helpful for development, but can sometimes cause issues
    # with resources or run initialization twice. Set to False if you encounter problems.
    app.run(host='0.0.0.0', port=5000, debug=True, use_reloader=True)