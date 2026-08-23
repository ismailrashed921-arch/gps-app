const fs = require('fs');
const path = 'app/src/main/java/com/kilagbe/fakegps/MainActivity.kt';
let content = fs.readFileSync(path, 'utf8');

// add `val context = LocalContext.current` inside SavedScreen
content = content.replace(
  'fun SavedScreen(repo: LocationRepository, saved: List<SavedLocation>) {\n    val scope = rememberCoroutineScope()',
  'fun SavedScreen(repo: LocationRepository, saved: List<SavedLocation>) {\n    val context = LocalContext.current\n    val scope = rememberCoroutineScope()'
);

// fix the bad variable name
content = content.replace(/LocalContext_forItem/g, 'context');

fs.writeFileSync(path, content, 'utf8');
console.log('Fixed.');
