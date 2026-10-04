import { useRef, useState } from 'react';
import type { ViewToken } from 'react-native';
import type { Post } from './api';

export function usePostVisibility() {
  const [visiblePosts, setVisiblePosts] = useState(new Set<number>());
  const config = useRef({ itemVisiblePercentThreshold: 20 }).current;
  const onViewableItemsChanged = useRef(({ viewableItems }: { viewableItems: ViewToken<Post>[] }) => {
    setVisiblePosts(new Set(viewableItems.filter(item => item.isViewable).map(item => item.item.id)));
  }).current;
  return { visiblePosts, viewabilityConfig: config, onViewableItemsChanged };
}
