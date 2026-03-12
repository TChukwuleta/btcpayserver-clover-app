Clover docs:
https://docs.clover.com/dev/docs/creating-custom-tender-apps#customer-facing-setup

Download Clover profiles, and also clover engine and app updater using this link:

https://sandbox.dev.clover.com/developers/dev-apks


Steps to  run on emulator:

Create an account: https://sandbox.dev.clover.com/

- On Android studio, `Tools` -> `Device Manager` -> `+` -> `Create Virtual Device`

- Add the Clover profile most preferably Clover Mini (3rd gen), API version => 29

- Install Clover engine:
adb install "C:\Users\UPTEE\source\repos\BTCPayProjects\TESTING_FOLDER\Clover\com.clover.engine-2411.apk"

- curl.exe https://raw.githubusercontent.com/clover/clover-android-sdk/master/scripts/install_apps.py -o install_apps.py

- In the emulator, go to settings, `Account` -> `Add an account` -> `enter your login details in your clover and click login`

- python install_apps.py =>  installs the Clover apps from the server based on your developer account.

*** Make sure you give all access when requested.. 

- adb reboot

- After reboot, use `Launcher` as home, and not `Pixel Launcher`




