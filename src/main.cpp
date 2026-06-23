#include <Wire.h>
#include <Adafruit_MPU6050.h>
#include <Adafruit_Sensor.h>
#include <MadgwickAHRS.h>
#include "BluetoothSerial.h"
#include <math.h>


#if !defined(CONFIG_BT_ENABLED) || !defined(CONFIG_BLUEDROID_ENABLED)
#error "Le Bluetooth n'est pas activé !"
#endif


BluetoothSerial SerialBT;


// ---------- CONFIGURATION ----------
const int NUM_SENSORS = 6;
const int NUM_PCA     = 3;


const int pcaEnablePins[NUM_PCA] = {16, 17, 18};
const bool PCA_ENABLE_ACTIVE_HIGH = true;
const unsigned int PCA_SWITCH_US = 50;
const unsigned int PCA_SETTLE_US = 300;


const float RAD2DEG = 57.2958f;
const float DEG2RAD = 0.0174533f;


struct SensorConfig {
  uint8_t address;
  uint8_t pca;
  const char* tag;
  float    length;   // longueur du segment (cm) pour le calcul de position
};


SensorConfig sensorConfigs[NUM_SENSORS] = {
  {0x68, 0, "PG", 25.0f}, // poignet gauche  -> PCA1
  {0x69, 0, "EG", 30.0f}, // epaule gauche   -> PCA1
  {0x68, 1, "PD", 25.0f}, // poignet droit   -> PCA2
  {0x69, 1, "ED", 30.0f}, // epaule droite   -> PCA2
  {0x68, 2, "TE", 15.0f}, // tete            -> PCA3
  {0x69, 2, "NU", 10.0f}, // nuque           -> PCA3
};


Adafruit_MPU6050 mpus[NUM_SENSORS];
Madgwick filters[NUM_SENSORS];
bool sensorOk[NUM_SENSORS];


float rollV[NUM_SENSORS], pitchV[NUM_SENSORS], yawV[NUM_SENSORS], motionV[NUM_SENSORS];
float posXV[NUM_SENSORS], posYV[NUM_SENSORS], posZV[NUM_SENSORS];


// ---------- BIAIS GYROSCOPIQUE PAR CAPTEUR ----------
float gyroBiasX[NUM_SENSORS], gyroBiasY[NUM_SENSORS], gyroBiasZ[NUM_SENSORS];


unsigned long microsPerReading, microsPrevious;


// ---------- ÉTAT DE LA MACHINE ----------
enum EtatApp { ATTENTE_INIT, INITIALISE, STREAMING };
EtatApp etat = ATTENTE_INIT;

String commandeBuffer = "";


// ---------- GESTION DES PCA ----------
void setPca(int idx, bool on) {
  bool level = PCA_ENABLE_ACTIVE_HIGH ? on : !on;
  digitalWrite(pcaEnablePins[idx], level ? HIGH : LOW);
}


void allPcaOff() {
  for (int i = 0; i < NUM_PCA; i++) setPca(i, false);
}


void selectPca(int idx) {
  allPcaOff();
  delayMicroseconds(PCA_SWITCH_US);
  setPca(idx, true);
  delayMicroseconds(PCA_SETTLE_US);
}


// ---------- CALIBRATION DU BIAIS GYRO ----------
void calibrerBiaisGyro() {
  const int NB_ECHANTILLONS = 300;
  float sommeX[NUM_SENSORS] = {0};
  float sommeY[NUM_SENSORS] = {0};
  float sommeZ[NUM_SENSORS] = {0};

  for (int iter = 0; iter < NB_ECHANTILLONS; iter++) {
    for (int p = 0; p < NUM_PCA; p++) {
      selectPca(p);
      for (int i = 0; i < NUM_SENSORS; i++) {
        if (sensorConfigs[i].pca != p || !sensorOk[i]) continue;
        sensors_event_t a, g, temp;
        mpus[i].getEvent(&a, &g, &temp);
        sommeX[i] += g.gyro.x * RAD2DEG;
        sommeY[i] += g.gyro.y * RAD2DEG;
        sommeZ[i] += g.gyro.z * RAD2DEG;
      }
    }
    allPcaOff();
    delay(5);
  }

  for (int i = 0; i < NUM_SENSORS; i++) {
    if (!sensorOk[i]) continue;
    gyroBiasX[i] = sommeX[i] / NB_ECHANTILLONS;
    gyroBiasY[i] = sommeY[i] / NB_ECHANTILLONS;
    gyroBiasZ[i] = sommeZ[i] / NB_ECHANTILLONS;
    Serial.printf("Biais %s : X=%.3f Y=%.3f Z=%.3f\n",
                  sensorConfigs[i].tag, gyroBiasX[i], gyroBiasY[i], gyroBiasZ[i]);
  }
}


// ---------- LECTURE D'UN CAPTEUR ----------
void readSensor(int i) {
  if (!sensorOk[i]) return;
  sensors_event_t a, g, temp;
  mpus[i].getEvent(&a, &g, &temp);


  float gx = g.gyro.x * RAD2DEG - gyroBiasX[i];
  float gy = g.gyro.y * RAD2DEG - gyroBiasY[i];
  float gz = g.gyro.z * RAD2DEG - gyroBiasZ[i];
  filters[i].updateIMU(gx, gy, gz, a.acceleration.x, a.acceleration.y, a.acceleration.z);


  rollV[i]  = filters[i].getRoll();
  pitchV[i] = filters[i].getPitch();
  yawV[i]   = filters[i].getYaw();


  // Actimétrie
  float svm = sqrt(a.acceleration.x * a.acceleration.x +
                   a.acceleration.y * a.acceleration.y +
                   a.acceleration.z * a.acceleration.z);
  float motion = fabs(svm - 9.81f);
  if (motion < 0.2f) motion = 0.0f;
  motionV[i] = motion;


  // Position X/Y/Z : extrémité du segment à partir des angles
  float p = pitchV[i] * DEG2RAD;
  float y = yawV[i]   * DEG2RAD;
  float L = sensorConfigs[i].length;
  posXV[i] = L * cos(y) * cos(p);
  posYV[i] = L * sin(p);
  posZV[i] = L * sin(y) * cos(p);
}


// ---------- LECTURE DES COMMANDES BLUETOOTH ----------
void lireCommandes() {
  while (SerialBT.available()) {
    char c = SerialBT.read();
    if (c == '\n') {
      commandeBuffer.trim();

      if (commandeBuffer == "INIT") {
        Serial.println("Calibration du biais gyro en cours...");
        calibrerBiaisGyro();

        for (int i = 0; i < NUM_SENSORS; i++) {
          if (sensorOk[i]) {
            filters[i].begin(100);
            rollV[i]  = 0;
            pitchV[i] = 0;
            yawV[i]   = 0;
            motionV[i] = 0;
            posXV[i]  = 0;
            posYV[i]  = 0;
            posZV[i]  = 0;
          }
        }
        etat = INITIALISE;
        SerialBT.print("INIT_OK\n");
        Serial.println("Initialisation effectuee");
      }
      else if (commandeBuffer == "START") {
        etat = STREAMING;
        SerialBT.print("START_OK\n");
        Serial.println("Streaming demarre");
      }

      commandeBuffer = "";
    } else if (c != '\r') {
      commandeBuffer += c;
    }
  }
}


void setup() {
  Serial.begin(115200);
  delay(2000);
  Serial.println("\n--- DEMARRAGE 6 CAPTEURS ---");


  for (int i = 0; i < NUM_PCA; i++) pinMode(pcaEnablePins[i], OUTPUT);
  allPcaOff();


  Wire.begin(21, 22);
  Wire.setClock(400000);


  for (int p = 0; p < NUM_PCA; p++) {
    selectPca(p);
    for (int i = 0; i < NUM_SENSORS; i++) {
      if (sensorConfigs[i].pca != p) continue;
      if (mpus[i].begin(sensorConfigs[i].address, &Wire)) {
        sensorOk[i] = true;
        mpus[i].setAccelerometerRange(MPU6050_RANGE_8_G);
        mpus[i].setGyroRange(MPU6050_RANGE_500_DEG);
        mpus[i].setFilterBandwidth(MPU6050_BAND_44_HZ);
        filters[i].begin(100);
        Serial.printf("Capteur %s (0x%02X, PCA%d) : OK\n",
                      sensorConfigs[i].tag, sensorConfigs[i].address, p + 1);
      } else {
        sensorOk[i] = false;
        Serial.printf("Capteur %s (0x%02X, PCA%d) : INTROUVABLE\n",
                      sensorConfigs[i].tag, sensorConfigs[i].address, p + 1);
      }
    }
  }

  Serial.printf("sensorOk: PG=%d EG=%d PD=%d ED=%d TE=%d NU=%d\n",
    sensorOk[0], sensorOk[1], sensorOk[2], 
    sensorOk[3], sensorOk[4], sensorOk[5]);
    
  allPcaOff();


  microsPerReading = 1000000 / 100;
  microsPrevious   = micros();


  SerialBT.begin("ESP32_Acti_3D");
  Serial.println("Pret. Associe 'ESP32_Acti_3D'.");
  Serial.println("En attente de la commande INIT...\n");
}


void loop() {
  lireCommandes();

  // Tant que le streaming n'est pas démarré, on ne fait rien d'autre
  if (etat != STREAMING) return;

  unsigned long microsNow = micros();
  if (microsNow - microsPrevious >= microsPerReading) {

    for (int p = 0; p < NUM_PCA; p++) {
      selectPca(p);
      for (int i = 0; i < NUM_SENSORS; i++) {
        if (sensorConfigs[i].pca == p) readSensor(i);
      }
    }
    allPcaOff();

    // Trame étiquetée : TAG,rotX,rotY,rotZ,actim,posX,posY,posZ
    // posY contient pitchV[i] (angle en degrés, déjà corrigé du biais gyro)
    char buf[512];
    int len = 0;
    for (int i = 0; i < NUM_SENSORS; i++) {
      len += snprintf(buf + len, sizeof(buf) - len,
                "%s,%.1f,%.1f,%.1f,%.2f,%.1f,%.1f,%.1f%s",
                sensorConfigs[i].tag,
                rollV[i], pitchV[i], yawV[i], motionV[i],
                posXV[i], pitchV[i], posZV[i],
                (i < NUM_SENSORS - 1) ? "," : "\n");
    }

    Serial.print(buf);
    if (SerialBT.hasClient()) SerialBT.print(buf);

    microsPrevious += microsPerReading;
  }
}