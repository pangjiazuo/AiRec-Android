param(
    [string]$AndroidSdk = '',
    [string]$JavaHome = '',
    [switch]$SkipTests,
    [switch]$BuildDeviceTests
)

$ErrorActionPreference = 'Stop'
$ProjectDirectory = $PSScriptRoot
$PreviousJavaHome = $env:JAVA_HOME
$PreviousAndroidHome = $env:ANDROID_HOME
$BuildStarted = Get-Date

try {
    if (-not $JavaHome) {
        $StudioJava = Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'
        $JavaHome = if (Test-Path -LiteralPath (Join-Path $StudioJava 'bin\java.exe')) { $StudioJava } else { $env:JAVA_HOME }
    }
    if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe'))) {
        throw '找不到 JDK。请安装 Android Studio，或传入 -JavaHome 指向 JDK 17 以上版本；JDK 25 已验证。'
    }
    if (-not $AndroidSdk) {
        $AndroidSdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
    }
    if (-not (Test-Path -LiteralPath $AndroidSdk -PathType Container)) {
        throw '找不到 Android SDK。请用 Android Studio SDK Manager 安装，或传入 -AndroidSdk。'
    }
    $env:JAVA_HOME = $JavaHome
    $env:ANDROID_HOME = $AndroidSdk
    $LocalProperties = Join-Path $ProjectDirectory 'local.properties'
    $SdkLine = 'sdk.dir=' + $AndroidSdk.Replace('\', '/').Replace(':', '\:')
    $OtherProperties = if (Test-Path -LiteralPath $LocalProperties) {
        @(Get-Content -LiteralPath $LocalProperties | Where-Object { $_ -notmatch '^\s*sdk\.dir\s*=' })
    } else { @() }
    $PropertiesText = (@($SdkLine) + $OtherProperties) -join "`n"
    [System.IO.File]::WriteAllText($LocalProperties, $PropertiesText + "`n", [System.Text.UTF8Encoding]::new($false))

    $OutputDirectory = Join-Path $ProjectDirectory 'dist'
    New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
    $BuildLog = Join-Path $OutputDirectory 'build.log'
    $GradleTasks = if ($SkipTests) { @(':app:assembleDebug') } else { @(':app:testDebugUnitTest', ':app:lintDebug', ':app:assembleDebug') }
    if ($BuildDeviceTests) { $GradleTasks += ':app:assembleDebugAndroidTest' }
    Push-Location -LiteralPath $ProjectDirectory
    try {
        # Wrapper 首次下载固定版本 Gradle，构建依赖只使用官方仓库。
        & .\gradlew.bat @GradleTasks '--console=plain' '--no-daemon' 2>&1 | Tee-Object -FilePath $BuildLog
        $GradleExit = $LASTEXITCODE
        if ($GradleExit -ne 0) { throw "Android 构建失败（退出码 $GradleExit），详见 $BuildLog" }
    } finally {
        Pop-Location
    }

    $BuiltApk = Join-Path $ProjectDirectory 'app\build\outputs\apk\debug\app-debug.apk'
    if (-not (Test-Path -LiteralPath $BuiltApk)) { throw 'Gradle 未生成预期的 APK。' }
    # 产物名称跟随工程版本，避免升级后仍交付旧版本文件名。
    $AppBuildConfig = Get-Content -LiteralPath (Join-Path $ProjectDirectory 'app\build.gradle.kts') -Raw
    $VersionMatch = [regex]::Match($AppBuildConfig, 'versionName\s*=\s*"([0-9]+\.[0-9]+\.[0-9]+)"')
    if (-not $VersionMatch.Success) { throw '无法读取安卓应用版本号。' }
    $ApkFileName = 'smart-recorder-' + $VersionMatch.Groups[1].Value + '-debug.apk'
    $DeliveredApk = Join-Path $OutputDirectory $ApkFileName
    Copy-Item -LiteralPath $BuiltApk -Destination $DeliveredApk -Force
    $ApkHash = (Get-FileHash -LiteralPath $DeliveredApk -Algorithm SHA256).Hash.ToLowerInvariant()
    [System.IO.File]::WriteAllText((Join-Path $OutputDirectory 'SHA256SUMS.txt'), "$ApkHash  $ApkFileName`n", [System.Text.UTF8Encoding]::new($false))
    Write-Host "APK：$DeliveredApk"
    Write-Host "SHA256：$ApkHash"
    Write-Host "耗时：$([math]::Round(((Get-Date) - $BuildStarted).TotalSeconds)) 秒"
} finally {
    $env:JAVA_HOME = $PreviousJavaHome
    $env:ANDROID_HOME = $PreviousAndroidHome
}
