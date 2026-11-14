// Wird nach "cap add android" ausgeführt: fügt die Handy-Module (Bildschirmzeit, Health Connect, Benachrichtigungen),
// die Berechtigungen, Kotlin und die feste Signatur ein.
const fs = require('fs'), path = require('path');
function must(c, m) { if (!c) { console.error('FEHLER: ' + m); process.exit(1); } }
const repo = path.join(__dirname, '..');
const androidDir = path.join(repo, 'android');
const root = path.join(androidDir, 'app');
const pkgDir = path.join(root, 'src', 'main', 'java', 'app', 'treckit', 'mobile');
must(fs.existsSync(pkgDir), 'Paketordner nicht gefunden: ' + pkgDir);

// 1. Module kopieren
['UsageStatsPlugin.java', 'HealthPlugin.kt', 'NotifPlugin.java', 'TreckitNotifService.java', 'SharePlugin.java'].forEach(function (f) {
  fs.copyFileSync(path.join(repo, 'plugin', f), path.join(pkgDir, f));
});
fs.writeFileSync(path.join(pkgDir, 'MainActivity.java'),
`package app.treckit.mobile;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(UsageStatsPlugin.class);
        registerPlugin(HealthPlugin.class);
        registerPlugin(NotifPlugin.class);
        registerPlugin(SharePlugin.class);
        super.onCreate(savedInstanceState);
    }
}
`);

// 2. Manifest: Berechtigungen, Health-Connect-Hinweise, Benachrichtigungs-Dienst
const HEALTH = ['STEPS', 'DISTANCE', 'ACTIVE_CALORIES_BURNED', 'TOTAL_CALORIES_BURNED', 'EXERCISE', 'FLOORS_CLIMBED', 'HYDRATION', 'NUTRITION', 'HEART_RATE', 'RESTING_HEART_RATE', 'WEIGHT', 'SLEEP', 'BLOOD_PRESSURE', 'BLOOD_GLUCOSE', 'BODY_FAT', 'OXYGEN_SATURATION', 'HEART_RATE_VARIABILITY', 'MENSTRUATION'];
const mfPath = path.join(root, 'src', 'main', 'AndroidManifest.xml');
let mf = fs.readFileSync(mfPath, 'utf8');
if (mf.indexOf('treckit-module') < 0) {
  if (mf.indexOf('xmlns:tools') < 0) mf = mf.replace('<manifest ', '<manifest xmlns:tools="http://schemas.android.com/tools" ');
  must(mf.indexOf('<application') > 0, 'Manifest ohne application');
  const perms = [
    '<!-- treckit-module -->',
    '<uses-permission android:name="android.permission.PACKAGE_USAGE_STATS" tools:ignore="ProtectedPermissions" />',
    '<uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" tools:ignore="QueryAllPackagesPermission" />'
  ].concat(HEALTH.map(function (h) { return '<uses-permission android:name="android.permission.health.READ_' + h + '" />'; }))
    .concat(['<queries>', '    <package android:name="com.google.android.apps.healthdata" />', '</queries>']);
  mf = mf.replace('<application', perms.join('\n    ') + '\n\n    <application');
  // Android 13 und älter: Health Connect zeigt über diese Aktion, wofür Track.it die Daten braucht
  const actIdx = mf.indexOf('android:name=".MainActivity"');
  must(actIdx > 0, 'MainActivity nicht im Manifest');
  const endFilter = mf.indexOf('</intent-filter>', actIdx);
  must(endFilter > 0, 'intent-filter der MainActivity nicht gefunden');
  mf = mf.slice(0, endFilter) + '</intent-filter>\n            <intent-filter>\n                <action android:name="androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE" />\n            </intent-filter>\n            <intent-filter android:label="In Track.it eintragen">\n                <action android:name="android.intent.action.SEND" />\n                <category android:name="android.intent.category.DEFAULT" />\n                <data android:mimeType="image/*" />\n                <data android:mimeType="text/csv" />\n                <data android:mimeType="text/comma-separated-values" />\n                <data android:mimeType="text/plain" />\n                <data android:mimeType="application/json" />\n            </intent-filter>' + mf.slice(endFilter + '</intent-filter>'.length);
  // Android 14 und neuer: dasselbe über einen Alias, außerdem der Dienst für Benachrichtigungen
  const extra = [
    '<activity-alias',
    '    android:name="ViewPermissionUsageActivity"',
    '    android:exported="true"',
    '    android:targetActivity=".MainActivity"',
    '    android:permission="android.permission.START_VIEW_PERMISSION_USAGE">',
    '    <intent-filter>',
    '        <action android:name="android.intent.action.VIEW_PERMISSION_USAGE" />',
    '        <category android:name="android.intent.category.HEALTH_PERMISSIONS" />',
    '    </intent-filter>',
    '</activity-alias>',
    '<service',
    '    android:name=".TreckitNotifService"',
    '    android:label="Track.it"',
    '    android:exported="true"',
    '    android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">',
    '    <intent-filter>',
    '        <action android:name="android.service.notification.NotificationListenerService" />',
    '    </intent-filter>',
    '</service>'
  ];
  must(mf.indexOf('</application>') > 0, 'Ende von application nicht gefunden');
  mf = mf.replace('</application>', '    ' + extra.join('\n        ') + '\n    </application>');
  fs.writeFileSync(mfPath, mf);
}

// 3. Health Connect braucht mindestens Android 8 (API 26)
const varPath = path.join(androidDir, 'variables.gradle');
let vars = fs.readFileSync(varPath, 'utf8');
must(/minSdkVersion\s*=\s*\d+/.test(vars), 'minSdkVersion nicht gefunden');
vars = vars.replace(/minSdkVersion\s*=\s*\d+/, 'minSdkVersion = 26');
fs.writeFileSync(varPath, vars);

// 4. Kotlin für das Health-Connect-Modul
const rootGradlePath = path.join(androidDir, 'build.gradle');
let rg = fs.readFileSync(rootGradlePath, 'utf8');
if (rg.indexOf('kotlin-gradle-plugin') < 0) {
  const m = rg.match(/classpath ['"]com\.android\.tools\.build:gradle:[^'"]+['"]/);
  must(m, 'Android-Gradle-Plugin im Haupt-build.gradle nicht gefunden');
  rg = rg.replace(m[0], m[0] + "\n        classpath 'org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.24'");
  fs.writeFileSync(rootGradlePath, rg);
}
const gPath = path.join(root, 'build.gradle');
let g = fs.readFileSync(gPath, 'utf8');
if (g.indexOf('kotlin-android') < 0) {
  must(g.indexOf("apply plugin: 'com.android.application'") >= 0, 'apply plugin com.android.application nicht gefunden');
  g = g.replace("apply plugin: 'com.android.application'", "apply plugin: 'com.android.application'\napply plugin: 'kotlin-android'");
  must(/\nandroid \{/.test(g), 'android-Block nicht gefunden');
  g = g.replace(/\nandroid \{/, "\nandroid {\n    compileOptions {\n        sourceCompatibility JavaVersion.VERSION_17\n        targetCompatibility JavaVersion.VERSION_17\n    }\n    kotlinOptions {\n        jvmTarget = '17'\n    }");
  must(/\ndependencies \{/.test(g), 'dependencies-Block nicht gefunden');
  g = g.replace(/\ndependencies \{/, '\ndependencies {\n    implementation "androidx.health.connect:connect-client:1.1.0-alpha07"\n    implementation "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3"');
}

// 5. feste Signatur, damit Updates über die vorhandene App installiert werden können
if (g.indexOf('treckit.keystore') < 0) {
  const ks = path.join(repo, 'treckit.keystore').replace(/\\/g, '/');
  must(g.indexOf('buildTypes {') > 0, 'buildTypes nicht gefunden');
  g = g.replace('buildTypes {', `signingConfigs {
        treckit {
            storeFile file('${ks}')
            storePassword 'treckit-debug'
            keyAlias 'treckit'
            keyPassword 'treckit-debug'
        }
    }
    buildTypes {`);
  g = g.replace(/(buildTypes \{\s*)/, '$1debug {\n            signingConfig signingConfigs.treckit\n        }\n        ');
}
fs.writeFileSync(gPath, g);
console.log('Android-Projekt angepasst.');
