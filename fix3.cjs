const fs = require('fs');
const path = 'app/src/main/java/com/kilagbe/fakegps/MainActivity.kt';
let content = fs.readFileSync(path, 'utf8');

const before = content.includes('LocalContext_forItem');
console.log('Contains LocalContext_forItem before:', before);

content = content.split('LocalContext_forItem').join('context');

const after = content.includes('LocalContext_forItem');
console.log('Contains LocalContext_forItem after:', after);

fs.writeFileSync(path, content, 'utf8');
