package com.example.thermometre;
import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.Toast;
import android.util.Log;
import android.bluetooth.BluetoothProfile;
import java.util.UUID; // Assurez-vous d'avoir cet import
import android.widget.TextView; // Et celui-ci
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// L'annotation @SuppressLint("MissingPermission") est utilisée ici car nous vérifions manuellement
// les permissions avant chaque appel sensible.
@SuppressLint("MissingPermission")
public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_PERMISSIONS_CODE = 1;
    private static final long SCAN_PERIOD = 10000; // Arrête le scan après 10 secondes

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private boolean isScanning = false;
    private Handler handler;
    private BluetoothGatt bluetoothGatt;
    private TextView temperatureTextView;

    // UI Components
    private Button scanButton;
    private ListView deviceListView;

    // Liste pour stocker les appareils trouvés et les afficher
    private ArrayAdapter<String> deviceListAdapter;
    private Map<String, BluetoothDevice> discoveredDevices = new HashMap<>();
    private static final UUID HEALTH_THERMOMETER_SERVICE_UUID = UUID.fromString("00001809-0000-1000-8000-00805f9b34fb");
    private static final UUID TEMPERATURE_MEASUREMENT_CHARACTERISTIC_UUID = UUID.fromString("00002a1c-0000-1000-8000-00805f9b34fb");
    private static final UUID CCC_DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");


    private final ActivityResultLauncher<Intent> requestEnableBluetoothLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK) {
                    Toast.makeText(this, "Bluetooth activé.", Toast.LENGTH_SHORT).show();
                    startBleScan(); // Démarrer le scan une fois le BT activé
                } else {
                    Toast.makeText(this, "L'activation du Bluetooth est nécessaire.", Toast.LENGTH_LONG).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialisation des vues
        scanButton = findViewById(R.id.scanButton);
        deviceListView = findViewById(R.id.deviceListView);
        temperatureTextView = findViewById(R.id.temperatureTextView);

        // Initialisation de l'adapter pour la ListView
        deviceListAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        deviceListView.setAdapter(deviceListAdapter);

        handler = new Handler(Looper.getMainLooper());

        final BluetoothManager bluetoothManager = getSystemService(BluetoothManager.class);
        if (bluetoothManager != null) {
            bluetoothAdapter = bluetoothManager.getAdapter();
        }

        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Cet appareil ne supporte pas le Bluetooth", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        scanButton.setOnClickListener(v -> {
            if (!isScanning) {
                checkAndRequestPermissions();
            } else {
                stopBleScan();
            }
        });

        // Ajouter un listener pour la sélection d'un appareil dans la liste
        deviceListView.setOnItemClickListener((parent, view, position, id) -> {
            stopBleScan(); // Arrêter le scan avant de tenter la connexion
            String deviceInfo = (String) parent.getItemAtPosition(position);
            String deviceAddress = deviceInfo.substring(deviceInfo.lastIndexOf("\n") + 1);
            BluetoothDevice device = discoveredDevices.get(deviceAddress);

            if (device != null) {
                connectToDevice(device);
            }
        });
    }

    private void checkAndRequestPermissions() {
        List<String> permissionsToRequest = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        }

        if (!permissionsToRequest.isEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toArray(new String[0]), REQUEST_PERMISSIONS_CODE);
        } else {
            ensureBluetoothIsOn();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS_CODE) {
            boolean allPermissionsGranted = true;
            for (int grantResult : grantResults) {
                if (grantResult != PackageManager.PERMISSION_GRANTED) {
                    allPermissionsGranted = false;
                    break;
                }
            }
            if (allPermissionsGranted) {
                ensureBluetoothIsOn();
            } else {
                Toast.makeText(this, "Les permissions sont requises pour scanner.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void ensureBluetoothIsOn() {
        if (!bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            requestEnableBluetoothLauncher.launch(enableBtIntent);
        } else {
            startBleScan(); // Démarrer le scan si le BT est déjà activé
        }
    }

    // Définition du ScanCallback
    private final ScanCallback leScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);
            BluetoothDevice device = result.getDevice();
            String deviceName = device.getName();
            String deviceAddress = device.getAddress();

            // On ajoute l'appareil seulement s'il a un nom et n'est pas déjà dans la liste
            if (deviceName != null && !deviceName.isEmpty() && !discoveredDevices.containsKey(deviceAddress)) {
                discoveredDevices.put(deviceAddress, device);
                String deviceInfo = deviceName + "\n" + deviceAddress;
                deviceListAdapter.add(deviceInfo);
                deviceListAdapter.notifyDataSetChanged();
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            super.onScanFailed(errorCode);
            Toast.makeText(MainActivity.this, "Erreur de scan: " + errorCode, Toast.LENGTH_SHORT).show();
            stopBleScan();
        }
    };

    private void startBleScan() {
        if (isScanning) return;

        // Vérifier les permissions avant de scanner
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Permission de scan Bluetooth manquante.", Toast.LENGTH_SHORT).show();
            return;
        }

        // Nettoyer la liste avant un nouveau scan
        deviceListAdapter.clear();
        discoveredDevices.clear();
        deviceListAdapter.notifyDataSetChanged();

        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (bluetoothLeScanner == null) {
            Toast.makeText(this, "Impossible d'obtenir le scanner BLE.", Toast.LENGTH_SHORT).show();
            return;
        }

        // Arrêter le scan après la période définie (SCAN_PERIOD)
        handler.postDelayed(this::stopBleScan, SCAN_PERIOD);

        isScanning = true;
        bluetoothLeScanner.startScan(leScanCallback);
        scanButton.setText("Arrêter le scan");
        Toast.makeText(this, "Scan en cours...", Toast.LENGTH_SHORT).show();
    }

    private void stopBleScan() {
        if (!isScanning || bluetoothLeScanner == null) return;

        // Vérifier la permission avant d'arrêter le scan
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Permission de scan Bluetooth manquante.", Toast.LENGTH_SHORT).show();
            return;
        }

        isScanning = false;
        bluetoothLeScanner.stopScan(leScanCallback);
        scanButton.setText("Rechercher les appareils");
        Toast.makeText(this, "Scan arrêté.", Toast.LENGTH_SHORT).show();
        handler.removeCallbacksAndMessages(null); // Annuler le postDelayed
    }
    // Callback pour gérer les événements de connexion GATT
    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            String deviceAddress = gatt.getDevice().getAddress();

            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.d("GattCallback", "Connecté avec succès au GATT de " + deviceAddress);
                    // Une fois connecté, on lance la découverte des services
                    // Il est important de lancer ceci sur le thread UI pour mettre à jour l'interface si besoin
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this, "Connecté à " + deviceAddress, Toast.LENGTH_SHORT).show();
                        // La permission BLUETOOTH_CONNECT est requise ici
                        bluetoothGatt.discoverServices();
                    });
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.d("GattCallback", "Déconnecté du GATT de " + deviceAddress);
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Déconnecté", Toast.LENGTH_SHORT).show());
                    closeGattConnection();
                }
            } else {
                Log.w("GattCallback", "Erreur GATT: " + status + " pour " + deviceAddress);
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Erreur de connexion GATT: " + status, Toast.LENGTH_SHORT).show());
                closeGattConnection();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("GattCallback", "Services découverts avec succès.");
                BluetoothGattService service = gatt.getService(HEALTH_THERMOMETER_SERVICE_UUID);
                if (service != null) {
                    BluetoothGattCharacteristic characteristic = service.getCharacteristic(TEMPERATURE_MEASUREMENT_CHARACTERISTIC_UUID);
                    if (characteristic != null) {
                        // S'abonner aux notifications pour cette caractéristique
                        subscribeToCharacteristic(characteristic);
                    } else {
                        Log.w("GattCallback", "Caractéristique de température non trouvée.");
                    }
                } else {
                    Log.w("GattCallback", "Service Health Thermometer non trouvé.");
                }
            } else {
                Log.w("GattCallback", "Échec de la découverte des services: " + status);
            }
        }
        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            // Vérifier si la notification vient de la bonne caractéristique
            if (TEMPERATURE_MEASUREMENT_CHARACTERISTIC_UUID.equals(characteristic.getUuid())) {
                byte[] data = characteristic.getValue();
                // Le premier octet contient des flags. La température est un float sur 4 octets (du 2ème au 5ème).
                // Voir la documentation du "Health Thermometer Service" pour le format exact.
                // Format: [flags, temp, temp, temp, temp, ...]
                if (data != null && data.length >= 5) {
                    // Le premier bit du flag indique si la température est en Celsius (0) ou Fahrenheit (1)
                    boolean isFahrenheit = (data[0] & 0x01) != 0;
                    // La température est une valeur float codée sur 4 octets (little-endian)
                    int tempMantissa = (data[3] << 16) | ((data[2] & 0xFF) << 8) | (data[1] & 0xFF);
                    int tempExponent = data[4];

                    // Calculer la valeur finale
                    float temperature = (float) (tempMantissa * Math.pow(10, tempExponent));

                    String unit = isFahrenheit ? "°F" : "°C";
                    Log.d("GattCallback", "Nouvelle température: " + temperature + unit);

                    // Mettre à jour l'interface utilisateur sur le thread principal
                    runOnUiThread(() -> {
                        temperatureTextView.setText(String.format("Température: %.2f %s", temperature, unit));
                    });
                }
            }
        }
    };

    // Méthode pour s'abonner aux notifications d'une caractéristique
    private void subscribeToCharacteristic(BluetoothGattCharacteristic characteristic) {
        // Activer les notifications localement pour cette caractéristique
        bluetoothGatt.setCharacteristicNotification(characteristic, true);

        // Écrire sur le descripteur CCC pour activer les notifications sur l'appareil distant
        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCC_DESCRIPTOR_UUID);
        if (descriptor != null) {
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            if (!bluetoothGatt.writeDescriptor(descriptor)) {
                Log.e("subscribeToCharacteristic", "Échec de l'écriture sur le descripteur");
            } else {
                Log.d("subscribeToCharacteristic", "Abonnement aux notifications en cours...");
            }
        }
    }

    // Méthode pour se connecter à un appareil BLE
    private void connectToDevice(BluetoothDevice device) {
        if (device == null) {
            return;
        }
        // Vérifier la permission BLUETOOTH_CONNECT sur Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Permission BLUETOOTH_CONNECT manquante.", Toast.LENGTH_SHORT).show();
            return;
        }

        // Fermer toute connexion existante avant d'en créer une nouvelle
        closeGattConnection();

        Log.d("BluetoothGatt", "Tentative de connexion à " + device.getAddress());
        // Le paramètre autoConnect à false permet une tentative de connexion directe
        bluetoothGatt = device.connectGatt(this, false, gattCallback);
    }

    // Méthode pour fermer la connexion GATT
    public void closeGattConnection() {
        if (bluetoothGatt != null) {
            // Vérifier la permission BLUETOOTH_CONNECT sur Android 12+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Permission BLUETOOTH_CONNECT manquante pour déconnecter.", Toast.LENGTH_SHORT).show();
                return;
            }
            bluetoothGatt.close();
            bluetoothGatt = null;
            Log.d("BluetoothGatt", "Connexion GATT fermée.");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closeGattConnection();
    }
}