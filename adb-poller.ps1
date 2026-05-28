$adb = "c:\Users\user\Desktop\Engineering\OPay\platform-tools\adb.exe"
$webhookUrl = "http://127.0.0.1:8080/api/v1/sms/webhook"
$lastProcessedId = 0

Write-Host "Waiting for device to be connected and authorized..."
while ($true) {
    $devices = & $adb devices
    if ($devices -match "\bdevice\b") {
        break
    }
    Start-Sleep -Seconds 2
}
Write-Host "Device connected!"

Write-Host "Fetching initial state..."
$output = & $adb shell "content query --uri content://sms/inbox --projection _id"
foreach ($line in $output) {
    if ($line -match "_id=(\d+)") {
        $id = [int]$matches[1]
        $lastProcessedId = [math]::Max($lastProcessedId, $id)
    }
}
Write-Host "Starting watch at SMS ID > $lastProcessedId"

while ($true) {
    $output = & $adb shell "content query --uri content://sms/inbox --projection _id:address:body"
    
    foreach ($line in $output) {
        if ($line -match "Row: \d+ _id=(\d+), address=(.*?), body=(.*)") {
            $id = [int]$matches[1]
            $address = $matches[2]
            $body = $matches[3]
            
            if ($id -gt $lastProcessedId) {
                if ($body -match "OPAY-") {
                    Write-Host "Found new OPay SMS: $body from $address"
                    
                    $payload = @{
                        payload = @{
                            sender = $address
                            message = $body
                        }
                    } | ConvertTo-Json -Depth 3
                    
                    try {
                        $null = Invoke-RestMethod -Uri $webhookUrl -Method POST -Body $payload -ContentType "application/json"
                        Write-Host "Successfully forwarded to local Java server."
                    } catch {
                        Write-Host "Failed to forward: $_"
                    }
                }
                $lastProcessedId = [math]::Max($lastProcessedId, $id)
            }
        }
    }
    Start-Sleep -Seconds 2
}
