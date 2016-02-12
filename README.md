# LocationProvider plugin #
This [Cordova][cordova] plugin implements __Android__ background service for location queries triggered by e.g. GCM notification. The plugin is also compatible with [PhoneGap Build][PGB].

## Supported Platforms ##
- __Android__

## Usage ##

#### Define the configuration  ####
```javascript
cordova.plugins.LocationProvider.setConfiguration(/*JSONObject*/ config);
```
#### Fetch and clear the Locate history ####
```javascript
cordova.plugins.LocationProvider.getAndClearHistory(successCallback);

function successCallback(/*JSONObject*/ history){
    // process the history
}
```

## Installation ##
The plugin can either be installed from git repository, from local file system through the [Command-line Interface][CLI] or cloud based through [PhoneGap Build][PGB].

#### Local development environment ####
From master:
```bash
# ~~ from master branch ~~
cordova plugin add https://github.com/ToniKorin/cordova-plugin-location-provider.git
```
from a local folder:
```bash
# ~~ local folder ~~
cordova plugin add cordova-plugin-location-provider --searchpath path
```
or to use the latest stable version:
```bash
# ~~ stable version ~~
cordova plugin add cordova-plugin-location-provider@1.2.0
```

To remove the plug-in, run the following command:
```bash
cordova plugin rm cordova-plugin-location-provider
```

#### PhoneGap Build ####
Add the following xml line to your config.xml:
```xml
<gap:plugin platform="android" name="cordova-plugin-location-provider" version="1.2.0" source="npm"/>
```

## History ##
Check the [Change Log][changelog].

## License ##

This software is released under the [Apache 2.0 License][apache2_license].

© 2015 Toni Korin

[cordova]: https://cordova.apache.org
[CLI]: http://cordova.apache.org/docs/en/edge/guide_cli_index.md.html#The%20Command-line%20Interface
[PGB]: http://docs.build.phonegap.com/en_US/index.html
[PGB_plugin]: https://build.phonegap.com/plugins/490
[changelog]: https://github.com/ToniKorin/cordova-plugin-location-provider/blob/master/CHANGELOG.md
[apache2_license]: http://opensource.org/licenses/Apache-2.0