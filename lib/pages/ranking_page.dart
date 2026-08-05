import 'package:flutter/material.dart';
import '../main.dart';

class _RankingCategory {
  final String name;
  final int base;
  const _RankingCategory(this.name, this.base);
}

const _kCategories = [
  _RankingCategory('画廊', 11),
  _RankingCategory('上传者', 21),
  _RankingCategory('标签', 31),
  _RankingCategory('H@H', 41),
  _RankingCategory('种源', 51),
  _RankingCategory('清理', 61),
  _RankingCategory('评分', 71),
];

const _kPeriods = [
  (0, '全部时间'),
  (1, '近一年'),
  (2, '近一月'),
  (4, '昨日'),
];

class RankingPage extends StatefulWidget {
  const RankingPage({super.key});

  @override
  State<RankingPage> createState() => _RankingPageState();
}

class _RankingPageState extends State<RankingPage> {
  int _category = 0;
  int _period = 0;

  String get _tlPath =>
      'toplist.php?tl=${_kCategories[_category].base + _kPeriods[_period].$1}';

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Theme.of(context).scaffoldBackgroundColor,
      appBar: AppBar(
        title: const Text('排行榜'),
        backgroundColor: Theme.of(context).appBarTheme.backgroundColor,
        foregroundColor: Theme.of(context).appBarTheme.foregroundColor,
      ),
      body: Column(
        children: [
          _buildChipRow(
            items: _kCategories.map((c) => c.name).toList(),
            selected: _category,
            onSelect: (i) => setState(() => _category = i),
          ),
          _buildChipRow(
            items: _kPeriods.map((p) => p.$2).toList(),
            selected: _period,
            onSelect: (i) => setState(() => _period = i),
          ),
          Expanded(
            child: CustomListView(
              key: ValueKey(_tlPath),
              path: _tlPath,
              title: '排行榜',
              showMenu: false,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildChipRow({
    required List<String> items,
    required int selected,
    required ValueChanged<int> onSelect,
  }) {
    final primary = Theme.of(context).colorScheme.primary;
    return SizedBox(
      height: 48,
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: 12),
        itemCount: items.length,
        separatorBuilder: (_, __) => const SizedBox(width: 8),
        itemBuilder: (context, i) {
          return ChoiceChip(
            label: Text(items[i]),
            selected: selected == i,
            selectedColor: primary,
            labelStyle: TextStyle(
              fontSize: 13,
              color: selected == i
                  ? Colors.white
                  : Theme.of(context).textTheme.bodyLarge?.color,
            ),
            onSelected: (_) => onSelect(i),
          );
        },
      ),
    );
  }
}
