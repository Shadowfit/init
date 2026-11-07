const fs = require('fs');
const QRCode = require('../../frontend/node_modules/qrcode-terminal/vendor/QRCode');
const ErrorLevel = require('../../frontend/node_modules/qrcode-terminal/vendor/QRCode/QRErrorCorrectLevel');

const url = process.argv[2];
const output = process.argv[3];

if (!url || !output) {
  throw new Error('Usage: node generate_expo_qr.cjs <url> <output-json>');
}

const qr = new QRCode(-1, ErrorLevel.M);
qr.addData(url);
qr.make();

const size = qr.getModuleCount();
const modules = Array.from({ length: size }, (_, row) =>
  Array.from({ length: size }, (_, col) => Boolean(qr.isDark(row, col)))
);

fs.writeFileSync(output, JSON.stringify({ url, size, modules }), 'utf8');
