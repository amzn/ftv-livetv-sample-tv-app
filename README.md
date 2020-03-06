## Overview
This Sample TV App is based on the google sample tv app: https://github.com/googlearchive/androidtv-Leanback
You can use this app as a reference to do the Live TV integration on Fire-TV for Live Content. 

## Live TV integration on Fire-TV
If your application provides live content, you can surface it in the Fire TV's Channel Guide and the "On Now" row on Fire TV's home screen as well as make it searchable. The process for integrating live content into the Fire TV browse and search experience follows the same steps as outlined in the standard Android documentation. You need to create a TvInputService and provide channel information for Fire TV to consume. Optionally, you can implement a few shortcuts and alternative options in your app. For example, you can rely on Amazon services to surface programming metadata, and playback can be handled within your application and launched through deeplinks instead of being embedded within the Live TV player native to all Fire TV devices.

## Fire TV supports the following Live TV features:
1. Linear content tiles in launcher “On Now” and Recents rows displaying the current programming information
2. Channel guide with 14 days of programming for each channel
3. Search for station and programming information for next 14 days
4. Alexa support for “Tune to [channel_name]”
5. Ability to favorite channels from browse and search experiences
6. Option to provide deep link to playback

## Program Fields that FireTV currently support
Currently in FireTV UI, those program fields are supported in program.java if developer chooses to use the tv.db(tif) as the only source for program metadata:
1. title: the title for the program
2. startTimeUtcMillis: the start time of the program, in format of millisecond in UTC time
3. endTimeUtcMillis: the end time of the program, in format of millisecond in UTC time
4. contentRating: the standard tv content rating. Ex: TV-PG
5. episodeTitle: the title of the specific episode of the playing program
6. shortDesciption: the short description of the program
7. longDescription: the long description of the program. If this field is provided, it will override the "shortDescription" above.

For the details: refer to "XmlTvParser.parseProgram()"

## References and Developer Guides
Refer to the "EpgSyncJobService.java" in Sample TV App, it demos two way of launching player activity:

- TvContractUtils.updateChannelsWithTif()
- TvContractUtils.updateChannelsWithTifDeepLink()

By using the first option, it will use the Amazon Live TV player. Playback of Live TV content is typically handled by the native live TV application on the device by interacting with your TvInputService.Session, no extra work is required to create your own player activity UI.  

By using the second option, you need to create your own Player Activity and the UI. And you need to find your own way to pass the channel information through the deeplink intent.
"DemoPlayerActivity.java" is a sample to demo this case, and it is still required to implement a TvInputService.Session even if deeplink intents are provided, so that you can get the video in the "preview" screen in FireTV UI. 

For other details, please refer to: https://developer.amazon.com/docs/fire-tv/live-tv-integration.html

## License
License under the Apache 2.0 license. See the LICENSE file for details.

## Version
Version 1.0 

## Notice
Images/videos used in this sample are courtesy of the Blender
Foundation, shared under copyright or Creative Commons license.

- Elephant's Dream: (c) copyright 2006, Blender Foundation / Netherlands Media Art Institute / www.elephantsdream.org
- Sintel: (c) copyright Blender Foundation | www.sintel.org
- Tears of Steel: (CC) Blender Foundation | mango.blender.org
- Big Buck Bunny: (c) copyright 2008, Blender Foundation / www.bigbuckbunny.org
