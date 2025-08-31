# Get the current version from the VERSION file
$versionFile = ".\VERSION"
if (-not (Test-Path $versionFile)) {
    Write-Host "VERSION file not found. Please create one with a starting version number (e.g., v0.8.4)." -ForegroundColor Red
    exit
}

$currentVersion = Get-Content -Path $versionFile
$versionParts = $currentVersion -split '\.'
$major = $versionParts[0]
$minor = $versionParts[1]
$patch = [int]$versionParts[2] + 1
$newVersion = "$major.$minor.$patch"

# Stage all changes
Write-Host "Adding all new and modified files to Git..."
git add .
Write-Host "Done."

# Prompt for a commit message
$commitMessage = Read-Host -Prompt "Enter your commit message"

# Commit the changes with the updated version and message
Write-Host "Committing changes..."
git commit -m "${newVersion}: ${commitMessage}"
Write-Host "Done."

# Push the changes to the remote repository
Write-Host "Pushing changes to remote repository..."
git push
Write-Host "Done."

# Update the VERSION file with the new version
Set-Content -Path $versionFile -Value $newVersion

Write-Host "Git workflow complete. New version is $newVersion." -ForegroundColor Green