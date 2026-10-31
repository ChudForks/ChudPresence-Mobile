param(
    [string]$Source = (Join-Path $PSScriptRoot '..\artwork\launcher-icon.png')
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$resourceRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\app\src\main\res'))
$artwork = [Drawing.Bitmap]::FromFile([IO.Path]::GetFullPath($Source))

function Write-LauncherBitmap([int]$Size, [string]$Path, [switch]$Adaptive) {
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($Path)) | Out-Null
    $bitmap = [Drawing.Bitmap]::new($Size, $Size, [Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $graphics = [Drawing.Graphics]::FromImage($bitmap)
    $attributes = [Drawing.Imaging.ImageAttributes]::new()
    try {
        $graphics.CompositingQuality = [Drawing.Drawing2D.CompositingQuality]::HighQuality
        $graphics.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $graphics.PixelOffsetMode = [Drawing.Drawing2D.PixelOffsetMode]::HighQuality
        $attributes.SetWrapMode([Drawing.Drawing2D.WrapMode]::TileFlipXY)
        $padding = if ($Adaptive) { [int]($Size / 6) } else { 0 }
        $visibleSize = $Size - 2 * $padding

        if ($Adaptive) {
            # Android shows the middle 72dp of a 108dp layer. Extend only the
            # artwork's edge colors into the extra 18dp motion margins.
            $corners = @(
                @(0, 0, 0, 0),
                @(($Size - $padding), 0, ($artwork.Width - 1), 0),
                @(0, ($Size - $padding), 0, ($artwork.Height - 1)),
                @(($Size - $padding), ($Size - $padding), ($artwork.Width - 1), ($artwork.Height - 1))
            )
            foreach ($corner in $corners) {
                $brush = [Drawing.SolidBrush]::new($artwork.GetPixel($corner[2], $corner[3]))
                try { $graphics.FillRectangle($brush, $corner[0], $corner[1], $padding, $padding) }
                finally { $brush.Dispose() }
            }
            $edges = @(
                @($padding, 0, $visibleSize, $padding, 0, 0, $artwork.Width, 1),
                @($padding, ($Size - $padding), $visibleSize, $padding, 0, ($artwork.Height - 1), $artwork.Width, 1),
                @(0, $padding, $padding, $visibleSize, 0, 0, 1, $artwork.Height),
                @(($Size - $padding), $padding, $padding, $visibleSize, ($artwork.Width - 1), 0, 1, $artwork.Height)
            )
            foreach ($edge in $edges) {
                $target = [Drawing.Rectangle]::new($edge[0], $edge[1], $edge[2], $edge[3])
                $graphics.DrawImage($artwork, $target, $edge[4], $edge[5], $edge[6], $edge[7], [Drawing.GraphicsUnit]::Pixel, $attributes)
            }
        }

        $target = [Drawing.Rectangle]::new($padding, $padding, $visibleSize, $visibleSize)
        $graphics.DrawImage($artwork, $target, 0, 0, $artwork.Width, $artwork.Height, [Drawing.GraphicsUnit]::Pixel, $attributes)
        $bitmap.Save($Path, [Drawing.Imaging.ImageFormat]::Png)
        Write-Output $Path
    }
    finally {
        $attributes.Dispose()
        $graphics.Dispose()
        $bitmap.Dispose()
    }
}

try {
    foreach ($density in @(
        @{ Name = 'mdpi'; Size = 48 },
        @{ Name = 'hdpi'; Size = 72 },
        @{ Name = 'xhdpi'; Size = 96 },
        @{ Name = 'xxhdpi'; Size = 144 },
        @{ Name = 'xxxhdpi'; Size = 192 }
    )) {
        Write-LauncherBitmap $density.Size (Join-Path $resourceRoot "mipmap-$($density.Name)\ic_launcher.png")
    }
    Write-LauncherBitmap 432 (Join-Path $resourceRoot 'drawable-nodpi\ic_launcher_artwork.png') -Adaptive
}
finally { $artwork.Dispose() }
