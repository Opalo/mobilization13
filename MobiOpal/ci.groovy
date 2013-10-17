#!/usr/bin/env groovy

import groovy.json.JsonSlurper

// projectDir
def projectDir = new File('.')

// ant builder
def ant = new AntBuilder()

// artifacts directory
def artifactsDir = new File('artifacts')
artifactsDir.deleteDir()
artifactsDir.mkdir()

// pbx file
def pbx = new File('MobiOpal.xcodeproj/project.pbxproj')

info('Using pbx file', pbx.canonicalPath)

// scheme name, scheme file
def schemeName = 'MobiOpal'
def schemeFile = new File("MobiOpal.xcodeproj/xcshareddata/xcschemes/${schemeName}.xcscheme")
def schemeXml = new XmlSlurper().parseText(schemeFile.text)

info('Using scheme name', schemeName)
info('Using scheme file', schemeFile.canonicalPath)

// configuration + target
def configuration = schemeXml.ArchiveAction.@buildConfiguration.text()
def targetBlueprintId = schemeXml.BuildAction.BuildActionEntries.BuildActionEntry.BuildableReference.@BlueprintIdentifier.text()

info('Using configuration', configuration)
info('Using target blueprint ID', targetBlueprintId)

// pbx to JSON
// plutil -convert json MobiOpal.xcodeproj/project.pbxproj -o -
def pbxToJSONCmd = ['plutil', '-convert', 'json', pbx.canonicalPath, '-o', '-']
def process = pbxToJSONCmd.execute(null, projectDir)

process.waitFor()

def pbxJSON = new JsonSlurper().parseText(process.text)
def targetJSON = pbxJSON.objects.find { it.value.isa == 'PBXNativeTarget' && it.key == targetBlueprintId }.value
def target = targetJSON.name

info('Using target', target)

// plist
def buildConfigurationsListId = targetJSON.buildConfigurationList

info('Using build configurations ID', buildConfigurationsListId)

def buildConfigurationsJSON = pbxJSON.objects.find { it.key == buildConfigurationsListId && it.value.isa == 'XCConfigurationList' }
def buildConfigurationsListJSON = buildConfigurationsJSON.value.buildConfigurations
def configurationJSON = pbxJSON.objects.find { 
    it.key in buildConfigurationsListJSON && 
    it.value.name == configuration &&
    it.value.isa == 'XCBuildConfiguration'
}

info('Using configuration ID', configurationJSON.key)

def plist = new File(projectDir, configurationJSON.value.buildSettings.INFOPLIST_FILE).canonicalPath

info('Found plist file', plist)

// plist to JSON
def plistToJSONCmd = ['plutil', '-convert', 'json', plist, '-o', '-']
process = plistToJSONCmd.execute(null, projectDir)

process.waitFor()

def plistJSON = new JsonSlurper().parseText(process.text)
def cfBundleVersion = plistJSON.CFBundleVersion
def cfBundleShortVersionString = plistJSON.CFBundleShortVersionString
info('Using CF Bundle Version',cfBundleVersion)
info('Using CF Bundle Short Version String', cfBundleShortVersionString)

// build settings
// xcodebuild -target MobiOpal -configuration Release -showBuildSettings
def showBuildSettingsCmd = ['xcodebuild', '-target', target, '-configuration', configuration, '-showBuildSettings']

process = showBuildSettingsCmd.execute(null, projectDir)
process.waitFor()

def buildSettings = process.text.split('\n').inject([:]) { result, line ->
    def splitted = line.split('=')
    splitted.size() == 2 ? result[splitted[0].trim()] = splitted[1].trim() : null
    result
}

// product names
def productName = buildSettings['PRODUCT_NAME']
def fullProductName = buildSettings['FULL_PRODUCT_NAME']

info('Using product name', productName)
info('Using full product name', fullProductName)

def artifactPrefix = "${productName}_${cfBundleShortVersionString}_${cfBundleVersion}"

// archive
// xcodebuild -scheme MobiOpal clean archive
def archiveCmd = ['xcodebuild', '-scheme', schemeName, 'clean', 'archive']

process = archiveCmd.execute(null, projectDir)

def archivePath = null

process.in.eachLine { line ->
    if (line.contains('CI_ARCHIVE_PATH'))
        archivePath = line.split('=')[1].trim()
}
process.err.eachLine { line -> println "ERR: ${line}" }

process.waitFor()

info("Found archive path", archivePath)

def xcarchiveFile = new File(artifactsDir, "${artifactPrefix}.xcarchive")
xcarchiveFile.mkdirs()

ant.copy(todir: "$xcarchiveFile.canonicalPath") {
    fileset(dir: "${new File(archivePath).canonicalPath}") {
        include(name: "**/*")
    }
}

info('Created xcarchive artifact', xcarchiveFile.canonicalPath)

// dSYM zip
def dSYMZip = new File(artifactsDir, "${artifactPrefix}_dSYM.zip")
def dSYMsPath = new File(xcarchiveFile, 'dSYMs').canonicalPath
info('Using dSYMs dir', dSYMsPath)
ant.zip(destfile: dSYMZip.canonicalPath, basedir: dSYMsPath) {
    fileset(dir: dSYMsPath) {
        include(name: "**/*")
    }
}

info('Created dSYM zip', dSYMZip.canonicalPath)

// ipa
// xcrun -sdk iphoneos PackageApplication -v <XCARCHIVE>/Products/Applications/*.app -o <IPA> -embed MobiOpal.mobileprovision
def appPath = new File(xcarchiveFile, "Products/Applications/$fullProductName").canonicalPath
def mobileprovision = new File(projectDir, 'MobiOpal.mobileprovision').canonicalPath
info('Using app file', appPath)
info('Using mobileprovision', mobileprovision)

def ipaPath = new File(artifactsDir, "${artifactPrefix}.ipa").canonicalPath
def ipaCmd = ['xcrun', '-sdk', 'iphoneos', 'PackageApplication', '-v', appPath, '-o', ipaPath, '-embed', mobileprovision]

process = ipaCmd.execute(null, projectDir)
process.waitFor()

info('Created IPA file', ipaPath)

def info(msg, value) {
    println "${msg.padRight(40)}: $value"
}