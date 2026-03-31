curl https://apis.itedus.cn/v1/chat/completions -H "Content-Type: application/json" -H "Authorization: Bearer sk-FNpOcM9t3GJ2EQHi0cEbFa9d7b7d45EaA1C1E73288F7AdA4" -d '{
  "model": "gpt-4o",
  "messages": [
    {
      "role": "user",
      "content": "1+1"
    }
  ]
}'