"""Fail the dev build if sensitive capture declarations return in the packaged APK."""
import os
from pathlib import Path
import subprocess
import sys


def verify(apk):
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if not sdk:
        raise ValueError('Set ANDROID_HOME')
    aapt = Path(sdk) / 'build-tools/36.1.0' / ('aapt2.exe' if os.name == 'nt' else 'aapt2')
    manifest = subprocess.check_output(
        [str(aapt), 'dump', 'xmltree', str(apk), '--file', 'AndroidManifest.xml'],
        text=True, encoding='utf-8')
    forbidden = [
        'android.permission.READ_SMS', 'android.permission.RECEIVE_SMS',
        'android.permission.SEND_SMS', 'android.permission.RECEIVE_MMS',
        'android.permission.RECEIVE_WAP_PUSH',
        'android.permission.BIND_NOTIFICATION_LISTENER_SERVICE',
        'android.service.notification.NotificationListenerService',
        'android.permission.BIND_ACCESSIBILITY_SERVICE',
        'com.dharaneesh.expensetracker.capture.SmsReceiver',
        'com.dharaneesh.expensetracker.capture.NotificationCaptureService',
    ]
    present = [item for item in forbidden if item in manifest]
    if present:
        raise ValueError('Forbidden capture declarations: ' + ', '.join(present))
    if 'com.dharaneesh.expensetracker.dev' not in manifest:
        raise ValueError('Expected dev package')
    print('Verified packaged dev manifest: no SMS access, SMS receiver, notification listener or accessibility service.')


if __name__ == '__main__':
    verify(Path(sys.argv[1]))
