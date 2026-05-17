mvn archetype:generate \
  -DarchetypeGroupId=cn.lcode.ai \
  -DarchetypeArtifactId=ai-agent-scaffold-lite-archetype \
  -DarchetypeVersion=1.0 \
  -X -DarchetypeCatalog=local

# 或直接列出所有本地 archetype 手动选择
# mvn archetype:generate -X -DarchetypeCatalog=local
