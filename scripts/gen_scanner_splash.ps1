# Generate the square splash PNG (black tile, centered B mark) for :scanner.
Add-Type -AssemblyName System.Drawing

$root = $PSScriptRoot | Split-Path
$logoPath = Join-Path $root "scanner\src\main\res\drawable-nodpi\logo_bamania.png"
$src = [System.Drawing.Image]::FromFile($logoPath)

# Square "B" crop from the wide logo (right ~85% of the height).
$cropW = [int]($src.Height * 0.85)
$rect = New-Object System.Drawing.Rectangle(($src.Width - $cropW), 0, $cropW, $src.Height)

$size = 768
$bmp = New-Object System.Drawing.Bitmap($size, $size)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.Clear([System.Drawing.Color]::Black)
$g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$pad = [int]($size * 0.16)
$inner = $size - 2 * $pad
$g.DrawImage($src, (New-Object System.Drawing.Rectangle($pad, $pad, $inner, $inner)), $rect, [System.Drawing.GraphicsUnit]::Pixel)
$g.Dispose()

$out = Join-Path $root "scanner\src\main\res\drawable-nodpi\splash_logo.png"
$bmp.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()
$src.Dispose()
Write-Host "splash written: $out"
