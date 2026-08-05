import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ehviewer_scaffold/src/rust/parser.dart';
import 'package:ehviewer_scaffold/widgets/gallery_item_widget.dart';
import 'package:ehviewer_scaffold/providers/settings_provider.dart';
import 'package:ehviewer_scaffold/providers/downloads_provider.dart';

// 渲染 GalleryItemWidget，检查所有文本节点的样式是否包含
// 下划线/黄色等异常装饰（用于排查"文字下面黄色双杠"问题）。
void main() {
  testWidgets('GalleryItemWidget 文本样式无黄色下划线装饰', (tester) async {
    const item = GalleryItem(
      gid: '12345/abcde',
      token: 'abcde',
      title: 'Test Gallery Title With Some Words',
      thumbUrl: 'https://example.com/thumb.jpg',
      category: 'artistcg',
      uploader: 'test_uploader',
      postDate: '2024-01-01',
    );

    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          appearanceProvider.overrideWith((ref) => AppearanceSettings()),
          downloadsProvider.overrideWith((ref) => DownloadsStatus()),
        ],
        child: MaterialApp(
          theme: AppearanceSettings().themeData,
          home: const Scaffold(
            body: GalleryItemWidget(item: item),
          ),
        ),
      ),
    );

    final anomalies = <String>[];
    for (final element in tester.allElements) {
      final renderObj = element.renderObject;
      if (renderObj is RenderParagraph) {
        final style = renderObj.text.style;
        final deco = style?.decoration;
        final color = style?.color;
        if (deco != null && deco != TextDecoration.none) {
          anomalies.add('decoration=$deco color=$color text="${renderObj.text.toPlainText()}"');
        }
        if (color != null) {
          final hsl = HSLColor.fromColor(color);
          if (hsl.hue >= 40 && hsl.hue <= 70 && hsl.saturation > 0.5) {
            anomalies.add('黄色系文字 color=$color text="${renderObj.text.toPlainText()}"');
          }
        }
      }
    }

    expect(anomalies, isEmpty,
        reason: '发现异常文本样式: $anomalies');
  });

  testWidgets('无 Scaffold 祖先时文本样式仍无黄色下划线（模拟独立搜索结果页）', (tester) async {
    const item = GalleryItem(
      gid: '12345/abcde',
      token: 'abcde',
      title: 'Test Gallery Title With Some Words',
      thumbUrl: 'https://example.com/thumb.jpg',
      category: 'artistcg',
      uploader: 'test_uploader',
      postDate: '2024-01-01',
    );

    // 不包 Scaffold，直接作为 MaterialPageRoute 的页面内容，复现
    // 详情页点标签 → CustomListView(standalone) 的场景。
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          appearanceProvider.overrideWith((ref) => AppearanceSettings()),
          downloadsProvider.overrideWith((ref) => DownloadsStatus()),
        ],
        child: MaterialApp(
          theme: AppearanceSettings().themeData,
          home: const GalleryItemWidget(item: item),
        ),
      ),
    );

    final anomalies = <String>[];
    for (final element in tester.allElements) {
      final renderObj = element.renderObject;
      if (renderObj is RenderParagraph) {
        final style = renderObj.text.style;
        final deco = style?.decoration;
        final color = style?.color;
        if (deco != null && deco != TextDecoration.none) {
          anomalies.add('decoration=$deco color=$color text="${renderObj.text.toPlainText()}"');
        }
        if (color != null) {
          final hsl = HSLColor.fromColor(color);
          if (hsl.hue >= 40 && hsl.hue <= 70 && hsl.saturation > 0.5) {
            anomalies.add('黄色系文字 color=$color text="${renderObj.text.toPlainText()}"');
          }
        }
      }
    }

    expect(anomalies, isEmpty,
        reason: '无 Scaffold 祖先时发现异常文本样式: $anomalies');
  });
}
