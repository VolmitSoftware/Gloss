/// Outbound links surfaced in the help dialog and the top bar.
library;

const String huiGitHubUrl = 'https://github.com/VolmitSoftware/Gloss';
const String huiDiscordUrl =
    'https://discord.com/invite/volmit-software-189665083817852928';
const String huiDocsUrl = 'https://docs.volmit.com/gloss';
const String huiSiteUrl = 'https://www.volmit.com';

/// Where the plugin keeps its data, images and container-preview documents,
/// quoted verbatim in help copy. Document files come from
/// [DocumentTypeAdapter.dataPath].
const String huiPluginFolder = 'plugins/Gloss/';
const String huiImageFolder = '${huiPluginFolder}images/';
const String huiPreviewFolder = '${huiPluginFolder}previews/';

/// One external destination in the help dialog's links.
class HuiLink {
  const HuiLink({
    required this.label,
    required this.url,
    required this.icon,
  });

  final String label;
  final String url;

  /// Name of the lucide glyph the help dialog resolves; keeping it a string
  /// keeps this file free of any UI dependency.
  final String icon;
}

const List<HuiLink> huiLinks = <HuiLink>[
  HuiLink(
    label: 'Documentation',
    url: huiDocsUrl,
    icon: 'book',
  ),
  HuiLink(
    label: 'GitHub',
    url: huiGitHubUrl,
    icon: 'github',
  ),
  HuiLink(
    label: 'Discord',
    url: huiDiscordUrl,
    icon: 'chat',
  ),
  HuiLink(
    label: 'volmit.com',
    url: huiSiteUrl,
    icon: 'globe',
  ),
];
