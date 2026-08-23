const fs = require('fs');
const path = 'app/src/main/java/com/kilagbe/fakegps/MainActivity.kt';
let lines = fs.readFileSync(path, 'utf8').split('\n');

// line 735 (index 734) is "    val scope = rememberCoroutineScope()" right after SavedScreen's signature
if (lines[734].includes('val scope = rememberCoroutineScope()') && !lines[734].includes('context')) {
  lines.splice(734, 0, '    val context = LocalContext.current');
  console.log('Inserted context line.');
} else {
  console.log('WARNING: line 735 did not match expected content, no insert done. Line was:', lines[734]);
}

let content = lines.join('\n');
content = content.replace(/LocalContext_forItem/g, 'context');

fs.writeFileSync(path, content, 'utf8');
console.log('Done.');
