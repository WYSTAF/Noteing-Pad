$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$Jar = Join-Path $Root 'gradle\wrapper\gradle-wrapper.jar'
$Expected = '2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046'
if (-not (Test-Path $Jar)) {
    $Temp = "$Jar.$([Guid]::NewGuid()).tmp"
    try {
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        $Url = 'https://raw.githubusercontent.com/gradle/gradle/v8.11.1/gradle/wrapper/gradle-wrapper.jar'
        Write-Host 'Downloading the official Gradle 8.11.1 wrapper JAR...'
        Invoke-WebRequest -UseBasicParsing -Uri $Url -OutFile $Temp -TimeoutSec 180
        $Actual = (Get-FileHash -Path $Temp -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($Actual -ne $Expected) { throw 'Wrapper checksum mismatch. Refusing to execute it.' }
        Move-Item -Path $Temp -Destination $Jar -Force
    } finally {
        if (Test-Path $Temp) { Remove-Item $Temp -Force }
    }
}
$Actual = (Get-FileHash -Path $Jar -Algorithm SHA256).Hash.ToLowerInvariant()
if ($Actual -ne $Expected) { throw 'Wrapper checksum mismatch. Refusing to execute it.' }
