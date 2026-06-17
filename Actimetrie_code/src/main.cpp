#include <Wire.h>
#include <Adafruit_MPU6050.h>
#include <Adafruit_Sensor.h>
#include <MadgwickAHRS.h>
#include "BluetoothSerial.h"

// Vérification de sécurité pour s'assurer que le Bluetooth Classic est bien activé sur ta carte
#if !defined(CONFIG_BT_ENABLED) || !defined(CONFIG_BLUEDROID_ENABLED)
#error
#endif

BluetoothSerial SerialBT;
Adafruit_MPU6050 mpu;
Madgwick filter;

unsigned long microsPerReading, microsPrevious;

void setup() {
  Serial.begin(115200);
  delay(2000); 

  Serial.println("\n--- DEMARRAGE DU SYSTEME ---");
  Wire.begin(21, 22);

  Serial.println("Recherche du MPU6050...");
  
  if (!mpu.begin(0x68, &Wire) && !mpu.begin(0x69, &Wire)) {
    Serial.println("ERREUR CRITIQUE: MPU6050 introuvable.");
    while (1) { delay(10); } 
  }
  
  Serial.println("MPU6050 Trouve ! Configuration en cours...");
  
  mpu.setAccelerometerRange(MPU6050_RANGE_8_G);
  mpu.setGyroRange(MPU6050_RANGE_500_DEG);
  mpu.setFilterBandwidth(MPU6050_BAND_44_HZ);

  filter.begin(100); 
  microsPerReading = 1000000 / 100;
  microsPrevious = micros();
  
  // --- INITIALISATION BLUETOOTH CLASSIC ---
  SerialBT.begin("ESP32_Acti_3D"); // C'est le nom qui apparaîtra dans tes réglages
  
  Serial.println("==================================================");
  Serial.println("Pret ! Va dans les parametres Bluetooth de ton telephone.");
  Serial.println("Associe l'appareil 'ESP32_Acti_3D'.");
  Serial.println("==================================================\n");
}

void loop() {
  unsigned long microsNow = micros();
  
  if (microsNow - microsPrevious >= microsPerReading) {
    sensors_event_t a, g, temp;
    mpu.getEvent(&a, &g, &temp);

    // Calculs
    float gx = g.gyro.x * 57.2958;
    float gy = g.gyro.y * 57.2958;
    float gz = g.gyro.z * 57.2958;

    filter.updateIMU(gx, gy, gz, a.acceleration.x, a.acceleration.y, a.acceleration.z);

    float roll = filter.getRoll();
    float pitch = filter.getPitch();
    float yaw = filter.getYaw();

    float raw_svm = sqrt(a.acceleration.x * a.acceleration.x + 
                         a.acceleration.y * a.acceleration.y + 
                         a.acceleration.z * a.acceleration.z);
    
    float motionIntensity = abs(raw_svm - 9.81);
    if (motionIntensity < 0.2) motionIntensity = 0.0; 

    // Envoi sur le terminal PC (VS Code) pour le debug
    Serial.printf("Rotation_X:%.1f, Elevation_Y:%.1f, Direction_Z:%.1f, Mouvement:%.2f\n", 
                  roll, pitch, yaw, motionIntensity);

    // Envoi via Bluetooth Classic (Uniquement si l'app Android est connectée au socket)
    if (SerialBT.hasClient()) {
      // On envoie la ligne de texte avec un vrai retour à la ligne (\n) à la fin
      SerialBT.printf("%.1f,%.1f,%.1f,%.2f\n", roll, pitch, yaw, motionIntensity);
    }

    microsPrevious = microsPrevious + microsPerReading;
  }
}