# Generate launcher icons for the :scanner module from the Bamania's Tech logo.
# Crops the square "B" mark from the wide logo, centers it on a black tile with
# padding (so launcher masks never clip it), and writes every density bucket.
Add-Type -AssemblyName System.Drawing

$root = $PSScriptRoot | Split-Path
$logoPath = Join-Path $root "scanner\src\main\res\drawable-nodpi\logo_bamania.png"
$src = [System.Drawing.Image]::FromFile($logoPath)

# The "B" occupies roughly the right 55% of the wide logo (1500x1024).
$cropW = [int]($src.Height * 0.85)
$cropX = $src.Width - $cropW
$cropY = 0
$rect = New-Object System.Drawing.Rectangle($cropX, $cropY, $cropW, $src.Height)

$sizes = @{ "mdpi" = 48; "hdpi" = 72; "xhdpi" = 96; "xxhdpi" = 144; "xxxhdpi" = 192 }
$fg = 108  # adaptive-icon foreground canvas (dp)

foreach ($dpi in $sizes.Keys) {
    $outDir = Join-Path $root ("scanner\src\main\res\mipmap-" + $dpi)
    New-Item -ItemType Directory -Force -Path $outDir | Out-Null

    # 1) Legacy square icon (black bg + full-bleed B mark)
    $bmp = New-Object System.Drawing.Bitmap($sizes[$dpi], $sizes[$dpi])
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.Clear([System.Drawing.Color]::Black)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $pad = [int]($sizes[$dpi] * 0.10)
    $inner = $sizes[$dpi] - 2 * $pad
    $g.DrawImage($src, (New-Object System.Drawing.Rectangle($pad, $pad, $inner, $inner)), $rect, [System.Drawing.GraphicsUnit]::Pixel)
    $g.Dispose()
    $bmp.Save((Join-Path $outDir "ic_launcher.png"), [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()

    # 2) Adaptive foreground: B scaled to ~55% of the 108dp canvas (mask-safe)
    $fgBmp = New-Object System.Drawing.Bitmap($fg, $fg)
    $g2 = [System.Drawing.Graphics]::FromImage($fgBmp)
    $g2.Clear([System.Drawing.Color]::Transparent)
    $g2.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $innerFg = [int]($fg * 0.55)
    $off = [int](($fg - $innerFg) / 2)
    $g2.DrawImage($src, (New-Object System.Drawing.Rectangle($off, $off, $innerFg, $innerFg)), $rect, [System.Drawing.GraphicsUnit]::Pixel)
    $g2.Dispose()
    $fgBmp.Save((Join-Path $outDir "ic_launcher_foreground.png"), [System.Drawing.Imaging.ImageFormat]::Png)
    $fgBmp.Dispose()
}

$src.Dispose()
Write-Host "scanner launcher icons written (5 densities, legacy + adaptive foreground)"
