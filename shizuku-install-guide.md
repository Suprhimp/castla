# Shizuku Installation Guide

Castla requires **Shizuku** to properly route network traffic to the Tesla browser. Because of Android's restrictions, we cannot bundle Shizuku or install it automatically for you. 

Please follow this step-by-step guide to install and configure Shizuku on your Android device.

## Step 1: Download and Install Shizuku

You can safely install Shizuku from the Google Play Store.

1. Open the Google Play Store on your Android device.
2. Search for **Shizuku** or click the link below:
   👉 [**Download Shizuku from Google Play**](https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api)
3. Tap **Install**.

*(Alternatively, if you prefer, you can download the APK from the [official Shizuku GitHub repository](https://github.com/RikkaApps/Shizuku/releases).)*

---

## Step 2: Enable Developer Options

To run Shizuku, you need to enable Android's Developer Options.

1. Open your phone's **Settings** app.
2. Scroll down and tap on **About phone**.
3. Tap on **Software information**.
4. Find the **Build number** and tap on it **7 times** quickly. 
5. You may be prompted to enter your PIN or pattern. Once done, you'll see a message saying *"You are now a developer!"*

---

## Step 3: Enable Wireless Debugging

Shizuku uses Wireless Debugging to grant Castla the necessary permissions without requiring a computer.

1. Go back to the main **Settings** screen.
2. Scroll down to the bottom and tap on **Developer options**.
3. Scroll down to the **Debugging** section.
4. Toggle on **Wireless debugging** (make sure you are connected to a Wi-Fi network).
5. Tap **Allow** when prompted.

---

## Step 4: Start Shizuku

Now that Wireless Debugging is on, you can start Shizuku.

1. Open the **Shizuku** app you installed in Step 1.
2. Look for the section titled **Start via Wireless debugging**.
3. Tap the **Pairing** button. 
4. A notification will appear asking you to open Developer Options. Tap it or go to **Settings > Developer options > Wireless debugging** (tap the text, not the toggle).
5. Tap **Pair device with pairing code**.
6. A 6-digit code will appear. Note this code and enter it into the Shizuku notification panel that appears at the top of your screen.
7. Once paired successfully, go back to the **Shizuku** app.
8. Tap **Start**.

You will see code quickly scrolling on your screen. Once it finishes, Shizuku will show **"Shizuku is running"** at the top.

---

## Step 5: Return to Castla

Now that Shizuku is running, open the **Castla** app again. 
Castla will automatically detect that Shizuku is running and finish configuring the network connection to your Tesla!

## Troubleshooting

- **"Wireless Debugging turns off automatically"**: Some devices disable Wireless Debugging when you disconnect from a Wi-Fi network. If you restart your phone, you will need to open Shizuku and tap "Start" again.
- **"Shizuku won't start"**: Make sure you have paired the device correctly and that Wireless Debugging is toggled ON. Restarting your phone and trying Step 4 again often fixes temporary issues.