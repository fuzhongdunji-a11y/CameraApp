import { defineStorage } from '@aws-amplify/backend';

export const storage = defineStorage({
  name: 'agrihead-storage',
  access: (allow) => ({
    // public/ 以下のファイルへの読み書きをゲスト（未ログイン）に許可
    'public/*': [
      allow.guest.to(['read', 'write'])
    ]
  })
});