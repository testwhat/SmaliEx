### About
This is forked from https://github.com/JesusFreke/smali  
The additional modification is to support convert oat file to dex, and able to smali/baksmali multi-dex.

Function concept:  
boot.oat -> extract optimized boot class dex files -> deoptimize to dex files  
app.odex(oat) -> reference boot dex files to deoptimize

Download latest version:  
https://github.com/testwhat/SmaliEx/releases/tag/snapshot

Build command:  
gradlew -b smaliex/build.gradle dist

Usage:  
Deoptimize boot classes (The output will be in "odex" and "dex" folders):  
&nbsp;&nbsp;java -jar oat2dex.jar boot &lt;boot.oat file&gt;  
Deoptimize application:  
&nbsp;&nbsp;java -jar oat2dex.jar &lt;app.odex&gt; &lt;boot-class-folder output from above&gt;  
Get odex from oat:  
&nbsp;&nbsp;java -jar oat2dex.jar odex &lt;oat file&gt;  
Get odex smali (with optimized opcode) from oat/odex:  
&nbsp;&nbsp;java -jar oat2dex.jar smali &lt;oat/odex file&gt;  
Deodex /system/framework/ from device (need to connect with adb):  
&nbsp;&nbsp;java -jar oat2dex.jar devfw

Limitation:  
- If debug infomration is trimmed (e.g. with android support library or proguarded), then it is unable to recover type information.
- Cannot recognize informal oat/dex format.

Used by:  
[JoelDroid](http://forum.xda-developers.com/android/software-hacking/script-app-joeldroid-lollipop-batch-t2980857)  
[SVADeodexerForArt](http://forum.xda-developers.com/galaxy-s5/general/tool-deodex-tool-android-l-t2972025)  
[PUMa - Patch Utility Manager](http://forum.xda-developers.com/showthread.php?t=1434946)
