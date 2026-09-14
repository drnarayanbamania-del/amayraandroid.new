const fs = require('fs'), zlib = require('zlib');
const data = fs.readFileSync('C:/Users/drnar/Desktop/MAYA_4.18.5_Complete_Reconstruction_Prompt.pdf');
let out = [];
const re = /stream\r?\n/g;
let m;
while ((m = re.exec(data))) {
  const start = m.index + m[0].length;
  const end = data.indexOf('endstream', start);
  if (end < 0) break;
  try { out.push(zlib.inflateSync(data.slice(start, end))); } catch (e) {}
}
const text = Buffer.concat(out).toString('latin1');
const strRe = /\((?:\\.|[^()\\])*\)/g;
const strs = [...text.matchAll(strRe)].map(x => x[0].slice(1, -1));
fs.writeFileSync('pdf_text.txt', strs.join(' '));
console.log(strs.length, 'strings');
