// Wird nach "cap add android" ausgeführt: fügt das Bildschirmzeit-Modul, die Berechtigung und die feste Signatur ein.
const fs = require('fs'), path = require('path');
function must(c, m) { if (!c) { console.error('FEHLER: ' + m); process.exit(1); } }
const root = path.join(__dirname, '..', 'android', 'app');
const pkgDir = path.join(root, 'src', 'main', 'java', 'app', 'treckit', 'mobile');
must(fs.existsSync(pkgDir), 'Paketordner nicht gefunden: ' + pkgDir);
fs.copyFileSync(path.join(__dirname, '..', 'plugin', 'UsageStatsPlugin.java'), path.join(pkgDir, 'UsageStatsPlugin.java'));
fs.writeFileSync(path.join(pkgDir, 'MainActivity.java'),
`package app.treckit.mobile;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(UsageStatsPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
`);
const mfPath = path.join(root, 'src', 'main', 'AndroidManifest.xml');
let mf = fs.readFileSync(mfPath, 'utf8');
if (mf.indexOf('PACKAGE_USAGE_STATS') < 0) {
  if (mf.indexOf('xmlns:tools') < 0) mf = mf.replace('<manifest ', '<manifest xmlns:tools="http://schemas.android.com/tools" ');
  must(mf.indexOf('<application') > 0, 'Manifest ohne application');
  mf = mf.replace('<application', '<uses-permission android:name="android.permission.PACKAGE_USAGE_STATS" tools:ignore="ProtectedPermissions" />\n    <uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" tools:ignore="QueryAllPackagesPermission" />\n    <application');
  fs.writeFileSync(mfPath, mf);
}
// feste Signatur, damit Updates über die vorhandene App installiert werden können
const gPath = path.join(root, 'build.gradle');
let g = fs.readFileSync(gPath, 'utf8');
if (g.indexOf('treckit.keystore') < 0) {
  const ks = path.join(__dirname, '..', 'treckit.keystore').replace(/\\/g, '/');
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
  fs.writeFileSync(gPath, g);
}
console.log('Android-Projekt angepasst.');
