// Manual composition of existing Phosphor SVG paths; no image generation.
const fs = require('node:fs');
const path = require('node:path');
const sharp = require('sharp');
const root = __dirname;
const sourcePath = name => fs.readFileSync(path.join(root, 'sources', name), 'utf8').match(/<path d="([^"]+)"/)[1];
const eye = sourcePath('eye-bold.svg').split('M128,84')[0]; // Retain both original outer contour subpaths, remove round pupil.
const terminal = sourcePath('terminal-bold.svg');
const mark = `<g transform="translate(89.6 89.6) scale(3.3)" fill="#C7EF88">
  <path id="phosphor-eye-contour" d="${eye}"/>
  <path id="phosphor-terminal-pupil" transform="translate(65 64) scale(.5)" d="${terminal}"/>
</g>`;
function svg(transparent) {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="0 0 1024 1024" role="img" aria-labelledby="title desc">
<title id="title">Peripheral</title>
<desc id="desc">Lime eye with a terminal prompt as its pupil. Composed from Phosphor Icons eye-bold and terminal-bold. MIT, copyright 2023 Phosphor Icons; see sources/LICENSE-phosphor.txt.</desc>
${transparent ? '' : '<rect width="1024" height="1024" fill="#303030"/>'}
${mark}
</svg>\n`;
}
(async () => {
  for (const transparent of [false, true]) {
    const name = transparent ? 'peripheral-logo-transparent' : 'peripheral-logo';
    const data = svg(transparent);
    fs.writeFileSync(path.join(root, name + '.svg'), data);
    for (const size of transparent ? [1024] : [1024, 256, 64, 32]) {
      await sharp(Buffer.from(data)).resize(size, size).png().toFile(path.join(root, `${name}-${size}.png`));
    }
  }
  // Inspection sheet: original raster sizes against the matching UI panel color.
  const samples = [];
  const sizes = [256, 64, 32];
  const positions = [[32, 32], [336, 128], [448, 144]];
  for (let i = 0; i < sizes.length; i++) samples.push({input: path.join(root, `peripheral-logo-${sizes[i]}.png`), left: positions[i][0], top: positions[i][1]});
  await sharp({create:{width:512,height:320,channels:4,background:'#1D1D1D'}}).composite(samples).png().toFile(path.join(root, 'size-check.png'));
  for (const file of ['peripheral-logo-1024.png','peripheral-logo-transparent-1024.png']) {
    const meta = await sharp(path.join(root,file)).metadata();
    console.log(file, {width:meta.width,height:meta.height,channels:meta.channels});
  }
})();
