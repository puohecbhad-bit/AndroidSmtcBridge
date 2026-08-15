The locally generated Android release key is stored in this directory on E: as:

  media-bridge-release.jks

Alias: media-bridge
Store/key password: androidsmtcbridge

Keep this key private and do not delete it. Android requires the same signing key
for future upgrades over an installed version. The distributable source archive
does not include the private key; the persistent E: source directory does.
