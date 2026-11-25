const { signup } = require('./signup.js');
let bad = 0;
if (signup({ email: '', name: 'A' }).ok !== false) { console.error('empty email was accepted'); bad++; }
if (signup({ email: 'not-an-email', name: 'A' }).ok !== false) { console.error('bad email was accepted'); bad++; }
if (signup({ email: 'a@b.co', name: 'A' }).ok !== true) { console.error('good email was refused'); bad++; }
if (bad) process.exit(1);
console.log('ok');
