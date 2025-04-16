from flask import Flask, request, jsonify

app = Flask(__name__)

@app.route('/upload', methods=['POST'])
def receive_cell_data():
    data = request.get_json()

    if not data:
        return jsonify({'status': 'error', 'message': 'No JSON data received'}), 400

    print("📡 Received Cell Data:")
    for key, value in data.items():
        print(f"{key}: {value}")

    return jsonify({'status': 'success', 'message': 'Data received'}), 200

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5000, debug=True)
