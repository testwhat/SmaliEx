### About
This is forked from https://code.google.com/p/smali/  
The additional modification is to support convert oat file to dex.

Function concept:  
boot.oat -> extract optimized boot class dex files -> deoptimize to dex files  
app.odex(oat) -> reference boot dex files to deoptimize

Download latest version:  
https://github.com/testwhat/SmaliEx/blob/master/smaliex-bin/oat2dex.jar?raw=true

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

Used by:  
[JoelDroid](http://forum.xda-developers.com/android/software-hacking/script-app-joeldroid-lollipop-batch-t2980857)  
[SVADeodexerForArt](http://forum.xda-developers.com/galaxy-s5/general/tool-deodex-tool-android-l-t2972025)  
[PUMa - Patch Utility Manager](http://forum.xda-developers.com/showthread.php?t=1434946)

<h3 style="border-bottom:2px solid #666">Original Readme<h3>
### About

smali/baksmali is an assembler/disassembler for the dex format used by dalvik, Android's Java VM implementation. The syntax is loosely based on Jasmin's/dedexer's syntax, and supports the full functionality of the dex format (annotations, debug info, line info, etc.)

Downloads are at  https://bitbucket.org/JesusFreke/smali/downloads. If you are interested in submitting a patch, feel free to send me a pull request here.

#### Support
- [github Issue tracker](https://github.com/JesusFreke/smali/issues) - For any bugs/issues/feature requests
- [#smali on freenode](http://webchat.freenode.net/?channels=smali) - Free free to drop by and ask a question. Don't expect an instant response, but if you hang around someone will respond.


#### Some useful links for getting started with smali

- [Official dex bytecode reference](https://source.android.com/devices/tech/dalvik/dalvik-bytecode.html)
- [Registers wiki page](https://github.com/JesusFreke/smali/wiki/Registers)
- [Types, Methods and Fields wiki page](https://github.com/JesusFreke/smali/wiki/TypesMethodsAndFields)
- [Official dex format reference](https://source.android.com/devices/tech/dalvik/dex-format.html)
