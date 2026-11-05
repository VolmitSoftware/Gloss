const path = require('node:path');

// Resolve the monorepo from this file so scripts also work outside gloss-web.
// Keep GLOSS_REPO as an alias for existing channel-filter test invocations.
const glossRepository = path.resolve(
  process.env.GLOSS_REPOSITORY || process.env.GLOSS_REPO || path.join(__dirname, '../../..'),
);

module.exports = {glossRepository};
